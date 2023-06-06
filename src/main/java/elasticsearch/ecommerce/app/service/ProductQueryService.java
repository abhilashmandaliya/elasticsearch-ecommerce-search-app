package elasticsearch.ecommerce.app.service;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.aggregations.AggregationBuilders;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.json.JsonData;
import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.fasterxml.jackson.databind.ObjectMapper;
import elasticsearch.ecommerce.app.entities.Query;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NByteArrayEntity;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseListener;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.filter.FiltersAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.filter.FiltersAggregator;
import org.elasticsearch.search.aggregations.metrics.MaxAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.MinAggregationBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Singleton
public class ProductQueryService {

    // TODO have search with aggs + custom scoring
    // TODO have search searching for impressum/jobs
    // TODO search with search as you type

    private static final String INDEX = "products";
    private static final Logger LOG = LoggerFactory.getLogger(ProductQueryService.class);

    private final ElasticsearchAsyncClient client;
    private final AerospikeClient aerospikeClient;
    private final ObjectMapper mapper;

    @Inject
    public ProductQueryService(ElasticsearchAsyncClient client, ObjectMapper mapper, AerospikeClient aerospikeClient) {
        this.client = client;
        this.mapper = mapper;
        this.aerospikeClient = aerospikeClient;
    }

    // search only across hits, don't include any aggregations
    public CompletableFuture<Response> searchProductsOnly(Query query) throws IOException {
        return asyncSearch(createFullTextSearchQuery(query), null, query.getFrom());
    }

    /**
     * This search filters for the specified aggregations, uses a post filter
     * The drawback of this solution is, that on selection of an aggregation, this is only appended to the post
     * filter, so the aggregation counts never change
     * <p>
     * Stock and Price are created as regular filters as part of the query, which indeed will change the aggregations
     */
    public CompletableFuture<Response> searchWithAggs(Query query) throws IOException {
        BoolQuery.Builder queryBuilder = QueryBuilders.bool();
        queryBuilder.must(createFullTextSearchQuery(query).build()._toQuery());
        // filter for price and stock, as they become range queries
        query.getFilters().stream().filter(filter -> List.of("stock", "price").contains(filter.getKey()))
                .forEach(filter -> queryBuilder.must(filter.toQuery()));

        // min_price
        Aggregation minPriceAgg = AggregationBuilders.min(builder -> builder.field("price"));
        // max_price
        Aggregation maxPriceAgg = AggregationBuilders.max(builder -> builder.field("price"));
        Aggregation byMaterialAgg = AggregationBuilders.terms(builder -> builder.name("by_material").field("material.keyword"));
        Aggregation byBrand = AggregationBuilders.terms(builder -> builder.name("by_brand").field("brand.keyword"));
        Aggregation byColor = AggregationBuilders.terms(builder -> builder.name("by_color").field("color.keyword"));
        Aggregation notInStockFilter = QueryBuilders.term(builder -> builder.field("stock").value(0).queryName("not_in_stock"))._toAggregation();
        Aggregation inStockFilter = new RangeQuery.Builder().queryName("in_stock").field("stock").gt(JsonData.of(1)).build()._toQuery()._toAggregation();
        FiltersAggregationBuilder inStockAgg = AggregationBuilders.filters("by_stock", inStockFilter, notInStockFilter);

        BoolQueryBuilder postFilterQuery = QueryBuilders.boolQuery();
        Map<String, List<Query.Filter>> byKey = query.getFilters().stream()
                .filter(filter -> !List.of("stock", "price").contains(filter.getKey()))
                .collect(Collectors.groupingBy(Query.Filter::getKey));
        for (Map.Entry<String, List<Query.Filter>> entry : byKey.entrySet()) {
            BoolQueryBuilder orQueryBuilder = QueryBuilders.boolQuery();

            for (Query.Filter filter : entry.getValue()) {
                orQueryBuilder.should(QueryBuilders.termQuery(filter.getKey() + ".keyword", filter.getValue()));
            }

            postFilterQuery.filter(orQueryBuilder);
        }

        postFilterQuery = postFilterQuery.filter().isEmpty() ? null : postFilterQuery;

        return asyncSearch(queryBuilder, postFilterQuery, query.getFrom(), byColor, byBrand, byMaterialAgg, minPriceAgg, maxPriceAgg, inStockAgg);
    }

    /**
     * This is the ultimate query, where all facets are filtered based on the fields of the other facets.
     * This will result in a bigger query, but return proper numbers
     */
    public CompletableFuture<Response> searchWithFilteredAggs(Query query) throws IOException {
        // this is the query for the total hits and the initial aggregations
        BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();
        queryBuilder.must(createFullTextSearchQuery(query));
        // filter for price and stock, as they become range queries
        query.getFilters().stream().filter(filter -> List.of("stock", "price").contains(filter.getKey()))
                .forEach(filter -> queryBuilder.must(filter.toQuery()));

        // TODO these also need to be possibly filtered!
        MinAggregationBuilder minPriceAgg = AggregationBuilders.min("min_price").field("price");
        MaxAggregationBuilder maxPriceAgg = AggregationBuilders.max("max_price").field("price");

        FiltersAggregator.KeyedFilter notInStockFilter = new FiltersAggregator.KeyedFilter("not_in_stock", QueryBuilders.termQuery("stock", 0));
        FiltersAggregator.KeyedFilter inStockFilter = new FiltersAggregator.KeyedFilter("in_stock", QueryBuilders.rangeQuery("stock").gt(0));
        FiltersAggregationBuilder inStockAgg = AggregationBuilders.filters("by_stock", inStockFilter, notInStockFilter);

        AggregationBuilder byMaterialAgg = createPossiblyFilteredAgg(query, "by_material", "material");
        AggregationBuilder byBrand = createPossiblyFilteredAgg(query, "by_brand", "brand");
        AggregationBuilder byColor = createPossiblyFilteredAgg(query, "by_color", "color");

        // additional post filter for material, brand and color
        BoolQueryBuilder postFilterQuery = QueryBuilders.boolQuery();
        Map<String, List<Query.Filter>> byKey = query.getFilters().stream()
                .filter(filter -> !List.of("stock", "price").contains(filter.getKey()))
                .collect(Collectors.groupingBy(Query.Filter::getKey));
        for (Map.Entry<String, List<Query.Filter>> entry : byKey.entrySet()) {
            BoolQueryBuilder orQueryBuilder = QueryBuilders.boolQuery();

            for (Query.Filter filter : entry.getValue()) {
                orQueryBuilder.should(QueryBuilders.termQuery(filter.getKey() + ".keyword", filter.getValue()));
            }

            postFilterQuery.filter(orQueryBuilder);
        }
        postFilterQuery = postFilterQuery.filter().isEmpty() ? null : postFilterQuery;


        return asyncSearch(queryBuilder, postFilterQuery, query.getFrom(), byColor, byBrand, byMaterialAgg, minPriceAgg, maxPriceAgg, inStockAgg);
    }

    /**
     * This is the ultimate query, where all facets are filtered based on the fields of the other facets.
     * This will result in a bigger query, but return proper numbers
     */
    public String searchProductFromAerospike(String id) throws IOException {
        Record record = aerospikeClient.get(null, new Key("root", null, id));
        return mapper.writeValueAsString(record);
    }

    /**
     * Creates regular text search query
     */
    private BoolQuery.Builder createFullTextSearchQuery(Query query) {
        BoolQuery.Builder queryBuilder = QueryBuilders.bool();
        MultiMatchQuery multiMatchQuery = QueryBuilders.multiMatch()
                .query(query.getQuery())
                .fields("name", "color", "brand", "material")
                .minimumShouldMatch("66%")
                .fuzziness("AUTO").build();
        queryBuilder.must(multiMatchQuery._toQuery());
        // increase scoring if we match in color, brand or material compared to product name
//        queryBuilder.should(QueryBuilders.matchQuery("material", query.getQuery()));
//        queryBuilder.should(QueryBuilders.matchQuery("color", query.getQuery()));
//        queryBuilder.should(QueryBuilders.matchQuery("brand", query.getQuery()));

        return queryBuilder;
    }

    private AggregationBuilder createPossiblyFilteredAgg(Query query, String aggregationName, String fieldName) {
        AggregationBuilder aggregationBuilder = AggregationBuilders.terms(aggregationName).field(fieldName + ".keyword");
        BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();
        query.getFilters().stream()
                .filter(filter -> !filter.getKey().equals(fieldName)) // filter out itself
                .forEach(filter -> queryBuilder.filter(filter.toQuery()));

        if (!queryBuilder.filter().isEmpty()) {
            aggregationBuilder = AggregationBuilders.filter(aggregationName, queryBuilder).subAggregation(aggregationBuilder);
        }
        return aggregationBuilder;
    }

    private CompletableFuture<Response> asyncSearch(QueryBuilder queryBuilder, QueryBuilder postFilterQuery, int from, AggregationBuilder... aggs) throws IOException {
        SearchRequest request = search(queryBuilder, postFilterQuery, from, aggs);
        final CompletableFuture<Response> future = new CompletableFuture<>();
        ResponseListener listener = newResponseListener(future);

        Request lowLevelRequest = new Request(HttpPost.METHOD_NAME, INDEX + "/_search");
        BytesRef source = XContentHelper.toXContent(request.source(), XContentType.JSON, ToXContent.EMPTY_PARAMS, true).toBytesRef();
        LOG.info("QUERY {}", source.utf8ToString());
        lowLevelRequest.setEntity(new NByteArrayEntity(source.bytes, source.offset, source.length, createContentType(XContentType.JSON)));

        client.getLowLevelClient().performRequestAsync(lowLevelRequest, listener);
        return future;
    }

    private SearchRequest search(QueryBuilder queryBuilder, QueryBuilder postFilterQuery, int from, AggregationBuilder... aggs) {
        SearchRequest request = new SearchRequest(INDEX);
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.size(16);
        searchSourceBuilder.from(from);
        searchSourceBuilder.query(queryBuilder);
        if (postFilterQuery != null) {
            searchSourceBuilder.postFilter(postFilterQuery);
        }
        for (AggregationBuilder agg : aggs) {
            searchSourceBuilder.aggregation(agg);
        }
        request.source(searchSourceBuilder);
        return request;
    }

    // copied from RequestConverts.java, as it is private
    @SuppressForbidden(reason = "Only allowed place to convert a XContentType to a ContentType")
    private static ContentType createContentType(final XContentType xContentType) {
        return ContentType.create(xContentType.mediaTypeWithoutParameters(), (Charset) null);
    }

    private ResponseListener newResponseListener(final CompletableFuture<Response> future) {
        return new ResponseListener() {

            @Override
            public void onSuccess(Response response) {
                future.complete(response);
            }

            @Override
            public void onFailure(Exception exception) {
                future.completeExceptionally(exception);
            }
        };
    }

}
