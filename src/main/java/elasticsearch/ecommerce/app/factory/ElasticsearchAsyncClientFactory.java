package elasticsearch.ecommerce.app.factory;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;

@Factory
public class ElasticsearchAsyncClientFactory {

    private final ElasticsearchAsyncClient client;

    @Inject
    public ElasticsearchAsyncClientFactory(@Property(name = "elasticsearch-username") String username,
                                           @Property(name = "elasticsearch-password") String password) {
        RestClientBuilder restClientBuilder = RestClient.builder(new HttpHost("localhost", 9200, "https"))
                .setHttpClientConfigCallback(httpClientBuilder -> {
                    BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                    credentialsProvider.setCredentials(
                            AuthScope.ANY, new UsernamePasswordCredentials(username, password)
                    );
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
//                            httpClientBuilder.setSSLHostnameVerifier((hostname, session) -> true);
                    return httpClientBuilder;
                });
        RestClientTransport restClientTransport =
                new RestClientTransport(restClientBuilder.build(), new JacksonJsonpMapper());
        this.client = new ElasticsearchAsyncClient(restClientTransport);
    }

    @Singleton
    public ElasticsearchAsyncClient getRestHighLevelClient() {
        return client;
    }

    @PreDestroy
    public void closeClient() throws IOException {
        IOUtils.closeQuietly(client._transport());
    }
}
