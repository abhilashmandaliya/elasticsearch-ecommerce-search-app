package elasticsearch.ecommerce.app.entities;

import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;

import java.util.ArrayList;
import java.util.List;

public class Query {

    private String query;
    private Integer from = 0;
    private List<Filter> filters = new ArrayList<>();

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public List<Filter> getFilters() {
        return filters;
    }

    public void setFilters(List<Filter> filters) {
        this.filters = filters;
    }

    public Integer getFrom() {
        return from;
    }

    public void setFrom(Integer from) {
        this.from = from;
    }

    public static final class Filter {
        private String key;
        private String value;
        private String from;
        private String to;
        private String type;

        public String getType() {
            return type;
        }

        public String getFrom() {
            return from;
        }

        public String getTo() {
            return to;
        }

        public String getKey() {
            return key;
        }

        public String getValue() {
            return value;
        }

        public co.elastic.clients.elasticsearch._types.query_dsl.Query toQuery() {
            if ("term".equals(type)) {
                return new TermQuery.Builder().value(this.value).field(this.key + ".keyword").build()._toQuery();
            } else if ("range".equals(type)) {
                return createRangeQueryBuilder(key, from, to);
            } else {
                throw new RuntimeException("Unknown type: " + type);
            }
        }

        private co.elastic.clients.elasticsearch._types.query_dsl.Query createRangeQueryBuilder(String name, String from, String to) {
            RangeQuery.Builder rangeQueryBuilder = new RangeQuery.Builder().queryName(name);
            if (!from.isEmpty()) {
                rangeQueryBuilder.from(from);
            }
            if (!to.isEmpty()) {
                rangeQueryBuilder.to(to);
            }
            return rangeQueryBuilder.build()._toQuery();
        }
    }
}
