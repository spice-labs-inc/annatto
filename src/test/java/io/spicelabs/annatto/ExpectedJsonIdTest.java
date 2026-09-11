package io.spicelabs.annatto;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every {@code src/test/resources/<ecosystem>/<name>-<version>-expected.json} carries an
 * {@code id} equal to {@code <ecosystem>/<name>-<version>}. The id is the test ID shared with
 * Surveyor's black-box integration tests (surveyor: tests/README.md), which read these files
 * from this repository at the commit the shipped jar was built from; it must be present,
 * derived from the file name, and unique. Needs no corpus download.
 */
class ExpectedJsonIdTest {

    private static final Path RESOURCES = Path.of("src/test/resources");
    private static final Gson GSON = new Gson();

    @Test
    @DisplayName("every expected JSON has id = <ecosystem>/<name>-<version>, and ids are unique")
    void idsMatchFileNamesAndAreUnique() throws IOException {
        List<String> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int count = 0;
        try (Stream<Path> files = Files.walk(RESOURCES)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith("-expected.json"))::iterator) {
                count++;
                String eco = p.getParent().getFileName().toString();
                String base = p.getFileName().toString();
                String expected = eco + "/" + base.substring(0, base.length() - "-expected.json".length());
                try (Reader r = Files.newBufferedReader(p)) {
                    JsonObject json = GSON.fromJson(r, JsonObject.class);
                    if (json == null || !json.has("id")) {
                        problems.add(p + ": missing id (expected \"" + expected + "\")");
                        continue;
                    }
                    String id = json.get("id").getAsString();
                    if (!expected.equals(id)) {
                        problems.add(p + ": id \"" + id + "\" != \"" + expected + "\"");
                    }
                    if (!seen.add(id)) {
                        problems.add(p + ": duplicate id \"" + id + "\"");
                    }
                }
            }
        }
        assertThat(count).as("expected JSON files under " + RESOURCES).isGreaterThan(0);
        assertThat(problems).as("expected JSON ids").isEmpty();
    }
}
