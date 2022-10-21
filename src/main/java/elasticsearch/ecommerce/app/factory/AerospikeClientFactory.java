package elasticsearch.ecommerce.app.factory;

import com.aerospike.client.AerospikeClient;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import org.apache.lucene.util.IOUtils;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Singleton;

@Factory
public class AerospikeClientFactory {

    private final AerospikeClient client;

    @Inject
    public AerospikeClientFactory(@Property(name = "aerospike-port") int port,
                                  @Property(name = "aerospike-hostname") String hostname) {
        this.client = new AerospikeClient(hostname, port);
    }

    @Singleton
    public AerospikeClient getAerospikeClient() {
        return client;
    }

    @PreDestroy
    public void closeClient() {
        IOUtils.closeWhileHandlingException(client);
    }
}
