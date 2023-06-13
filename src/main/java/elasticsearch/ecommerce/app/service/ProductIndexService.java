package elasticsearch.ecommerce.app.service;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.BatchRecord;
import com.aerospike.client.BatchWrite;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Operation;
import com.aerospike.client.policy.BatchPolicy;
import com.github.javafaker.Faker;
import com.github.javafaker.service.FakeValuesService;
import com.github.javafaker.service.RandomService;
import io.micronaut.http.HttpStatus;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.open.OpenIndexRequest;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CloseIndexRequest;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xcontent.XContentType;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Singleton
public class ProductIndexService extends ProductServiceBase {

    // Found via
    // for i in $(jot 1000) ; do curl -s -X HEAD "https://picsum.photos/id/$i/200/200" -w '%{http_code} %{url_effective}\n' | tee -a /tmp/head.log ; done
    // for i in $(grep "^404" /tmp/head.log | cut -d '/' -f 5) ; do echo -n "$i, " ; done
    private static final List<Integer> NON_EXISTING_IMAGE_IDS = List.of(86, 97, 105, 138, 148, 150, 205, 207, 224, 226, 245, 246, 262, 285, 286,
            298, 303, 332, 333, 346, 359, 394, 414, 422, 438, 462, 463, 470, 489, 540, 561, 578, 587, 589, 592, 595, 597, 601, 624, 632,
            636, 644, 647, 673, 697, 706, 707, 708, 709, 710, 711, 712, 713, 714, 720, 725, 734, 745, 746, 747, 748, 749, 750, 751, 752,
            753, 754, 759, 761, 762, 763, 771, 792, 801, 812, 843, 850, 854, 895, 897, 899, 917, 920, 934, 956, 963, 968);

    private static final int BRANDS_MAX = 10;

    private static final Faker faker = Faker.instance();

    @SuppressWarnings("deprecation")
    private final RestHighLevelClient client;
    private final AerospikeClient aerospikeClient;

    private static final List<String> colors = new ArrayList<>();
    private static final Random random = new Random();
    private final FakeValuesService fakeValuesService;
    private final ArrayList<String> part1;
    private final ArrayList<String> part2;

    @SuppressWarnings("deprecation")
    @Inject
    public ProductIndexService(RestHighLevelClient client, AerospikeClient aerospikeClient) {
        this.client = client;
        this.aerospikeClient = aerospikeClient;
        colors.add("pink");
        colors.add("yellow");
        RandomService randomService = new RandomService();
        this.fakeValuesService = new FakeValuesService(Locale.ENGLISH, randomService);

        this.part1 = new ArrayList<>();
        part1.add("on");
        part1.add("for");
        part1.add("with");

        this.part2 = new ArrayList<>();
        part2.add("specially for");
        part2.add("usable by");
        part2.add("suitable in");
    }

    /**
     * Create some random products, the user can specify how many
     *
     * @param count Number of products to be created
     */
    public CompletableFuture<HttpStatus> indexProducts(int count) {
        return CompletableFuture.supplyAsync(() -> {
            try {

                Set<String> brands = new HashSet<>();
                while (brands.size() < BRANDS_MAX) {
                    brands.add(faker.company().name());
                }
                String[] brandsArray = brands.toArray(new String[0]);

                boolean exists = client.indices().exists(new GetIndexRequest(INDEX), RequestOptions.DEFAULT);
                if (exists) {
                    client.indices().delete(new DeleteIndexRequest(INDEX), RequestOptions.DEFAULT);
                }

                try (Reader readerSettings = new InputStreamReader(Objects.requireNonNull(this.getClass().getResourceAsStream("/index-settings.json")));
                     Reader readerMappings = new InputStreamReader(Objects.requireNonNull(this.getClass().getResourceAsStream("/index-mappings.json")))) {
                    String settings = Streams.copyToString(readerSettings);
                    String mapping = Streams.copyToString(readerMappings);
                    CreateIndexRequest createIndexRequest = new CreateIndexRequest(INDEX).settings(settings, XContentType.JSON).mapping(mapping, XContentType.JSON);
                    client.indices().create(createIndexRequest, RequestOptions.DEFAULT);
                }

                boolean hasItems = false;
                ArrayList<BatchRecord> batchRecords = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    hasItems = true;
                    String productName = getProductName();
                    String productDesc = getProductDesc();
                    // This is to replace german prices with a comma with a proper decimal space...
                    double price = Double.parseDouble(faker.commerce().price(1, 1000).replace(",", "."));
                    String material = faker.commerce().material();
                    String color = colors.get(random.nextInt(colors.size()));
                    String id = faker.number().digits(12);
                    String brand = faker.options().nextElement(brandsArray);
                    // no text, we would need to deal with spaces and umlauts
                    int productImageId = faker.number().numberBetween(1, 1000);
                    while (NON_EXISTING_IMAGE_IDS.contains(productImageId)) {
                        productImageId = faker.number().numberBetween(1, 1000);
                    }
                    String productImage = "https://picsum.photos/id/" + productImageId + "/200/200?blur=1";
                    String brandLogo = faker.company().logo();
                    Date lastUpdated = faker.date().past(365, TimeUnit.DAYS);
                    int remainingStock = faker.number().numberBetween(0, 10);
                    int commission = faker.number().numberBetween(5, 20);

                    addIntoAerospikeBatch(batchRecords, productName, productDesc, price, color, material, id, productImage, brand, brandLogo, lastUpdated, remainingStock, commission);
                    if (batchRecords.size() >= 5000) {
                        batchRecords = putIntoAerospike(count, batchRecords);
                        hasItems = false;
                    }
                }

                if (hasItems) {
                    putIntoAerospike(count, batchRecords);
                }
                return HttpStatus.OK;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private String getProductDesc() {
        return StringUtils.join(new String[]{
                fakeValuesService.resolve("commerce.promotion_code.adjective", this, faker),
                fakeValuesService.resolve("commerce.promotion_code.noun", this, faker),
                part1.get(random.nextInt(part1.size())),
                fakeValuesService.resolve("commerce.product_name.adjective", this, faker),
                fakeValuesService.resolve("commerce.product_name.material", this, faker),
                fakeValuesService.resolve("commerce.product_name.product", this, faker),
                part2.get(random.nextInt(part2.size())),
                fakeValuesService.resolve("commerce.department", this, faker),
        }, " ");
    }

    private String getProductName() {
        return StringUtils.join(new String[]{
                fakeValuesService.resolve("commerce.product_name.material", this, faker),
                fakeValuesService.resolve("commerce.product_name.product", this, faker)}, " ");
    }

    private void addIntoAerospikeBatch(ArrayList<BatchRecord> batchRecords, String productName, String productDesc, double price,
                                       String color, String material, String id, String productImage, String brand,
                                       String brandLogo, Date lastUpdated, int remainingStock, int commission) {
        Key key = new Key("root", null, id);
        Operation[] operations = Operation.array(
                Operation.put(new Bin("productName", productName)),
                Operation.put(new Bin("productDesc", productDesc)),
                Operation.put(new Bin("price", price)),
                Operation.put(new Bin("color", color)),
                Operation.put(new Bin("material", material)),
                Operation.put(new Bin("productImage", productImage)),
                Operation.put(new Bin("brand", brand)),
                Operation.put(new Bin("brandLogo", brandLogo)),
                Operation.put(new Bin("lastUpdated", lastUpdated.toString())),
                Operation.put(new Bin("remainingStock", remainingStock)),
                Operation.put(new Bin("commission", commission)));
        batchRecords.add(new BatchWrite(key, operations));
    }

    private ArrayList<BatchRecord> putIntoAerospike(int count, ArrayList<BatchRecord> batchRecords) {
        BatchPolicy batchPolicy = new BatchPolicy();
        batchPolicy.sendKey = true;
        aerospikeClient.operate(batchPolicy, batchRecords);
        return new ArrayList<>(count);
    }

    public CompletableFuture<HttpStatus> configureSynonyms(String synonyms) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                client.indices().close(new CloseIndexRequest(INDEX), RequestOptions.DEFAULT);
                Settings settings = Settings.builder()
                        .putList("index.analysis.filter.my_synonym_filter.synonyms", synonyms.split("\n"))
                        .build();
                client.indices().putSettings(new UpdateSettingsRequest(INDEX).settings(settings), RequestOptions.DEFAULT);
                client.indices().open(new OpenIndexRequest().indices(INDEX), RequestOptions.DEFAULT);
                return HttpStatus.OK;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
