package elasticsearch.ecommerce.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;

public abstract class ProductServiceBase {
    protected static final String INDEX = "aerospike";

    protected static final ObjectMapper MAPPER = new ObjectMapper();
}
