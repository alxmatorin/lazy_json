package perfs.json.generate;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Генерирует JSON-файл заданного размера (по умолчанию 500 КБ).
 */
public final class JsonPayloadGenerator {

    public static final Path DEFAULT_FILE = Path.of("data.json");
    public static final int DEFAULT_SIZE_BYTES = 500 * 1024;

    private static final JsonFactory FACTORY = new JsonFactory();
    private static final String[] ACTIONS = {"created", "paid", "shipped", "returned", "cancelled"};
    private static final String[] TAGS = {"vip", "retail", "wholesale", "promo", "test", "legacy"};
    private static final String[] CITIES = {"Moscow", "Berlin", "Lisbon", "Tbilisi", "Belgrade", "Yerevan"};

    private final Random random;

    public JsonPayloadGenerator(long seed) {
        this.random = new Random(seed);
    }

    public static void main(String[] args) throws IOException {
        Path file = args.length > 0 ? Path.of(args[0]) : DEFAULT_FILE;
        int sizeBytes = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_SIZE_BYTES;

        long written = new JsonPayloadGenerator(42).generateTo(file, sizeBytes);
        System.out.printf("%s: %d bytes (%.1f KB)%n", file.toAbsolutePath(), written, written / 1024.0);
    }

    /** @return фактический размер файла в байтах */
    public long generateTo(Path file, int sizeBytes) throws IOException {
        try (CountingOutputStream out = new CountingOutputStream(Files.newOutputStream(file));
             JsonGenerator json = FACTORY.createGenerator(out)) {
            writeHeader(json);
            writeItemsUntil(json, out, sizeBytes);
            writeFooter(json);
        }
        return Files.size(file);
    }

    private void writeHeader(JsonGenerator json) throws IOException {
        json.writeStartObject();
        json.writeStringField("schema", "orders.v1");
        json.writeNumberField("generatedAt", System.currentTimeMillis());
        json.writeArrayFieldStart("items");
    }

    private void writeItemsUntil(JsonGenerator json, CountingOutputStream out, int sizeBytes) throws IOException {
        for (int id = 1; out.count() < sizeBytes; id++) {
            writeItem(json, id);
            json.flush();
        }
    }

    private void writeItem(JsonGenerator json, int id) throws IOException {
        json.writeStartObject();
        json.writeNumberField("id", id);
        json.writeStringField("name", "customer-" + id + "-" + randomWord(12));
        json.writeStringField("email", "user" + id + "@example.com");
        json.writeBooleanField("active", random.nextBoolean());
        json.writeNumberField("score", Math.round(random.nextDouble() * 10_000) / 100.0);
        writeTags(json);
        writeAddress(json);
        writeHistory(json);
        json.writeEndObject();
    }

    private void writeTags(JsonGenerator json) throws IOException {
        json.writeArrayFieldStart("tags");
        for (int i = 0, n = 1 + random.nextInt(3); i < n; i++) {
            json.writeString(pick(TAGS));
        }
        json.writeEndArray();
    }

    private void writeAddress(JsonGenerator json) throws IOException {
        json.writeObjectFieldStart("address");
        json.writeStringField("city", pick(CITIES));
        json.writeStringField("street", randomWord(10) + " street");
        json.writeNumberField("building", 1 + random.nextInt(200));
        json.writeStringField("zip", String.format("%05d", random.nextInt(100_000)));
        json.writeEndObject();
    }

    private void writeHistory(JsonGenerator json) throws IOException {
        json.writeArrayFieldStart("history");
        for (int i = 0, n = 2 + random.nextInt(4); i < n; i++) {
            json.writeStartObject();
            json.writeNumberField("ts", 1_700_000_000_000L + random.nextInt(Integer.MAX_VALUE));
            json.writeStringField("action", pick(ACTIONS));
            json.writeNumberField("amount", Math.round(random.nextDouble() * 100_000) / 100.0);
            json.writeEndObject();
        }
        json.writeEndArray();
    }

    private String randomWord(int length) {
        StringBuilder word = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            word.append((char) ('a' + random.nextInt(26)));
        }
        return word.toString();
    }

    private String pick(String[] values) {
        return values[random.nextInt(values.length)];
    }

    private void writeFooter(JsonGenerator json) throws IOException {
        json.writeEndArray();
        json.writeEndObject();
    }

    /** Считает записанные байты, чтобы остановиться на нужном размере. */
    private static final class CountingOutputStream extends FilterOutputStream {

        private long count;

        private CountingOutputStream(OutputStream out) {
            super(out);
        }

        private long count() {
            return count;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }
}
