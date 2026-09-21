package dev.bhavya.anvesh.search;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchFilterTest {

    @Test
    void noFilterAddsNothing() {
        List<Object> args = new ArrayList<>();
        assertThat(SearchRepository.filterSql(SearchRepository.Filter.NONE, args)).isEmpty();
        assertThat(SearchRepository.filterSql(null, args)).isEmpty();
        assertThat(args).isEmpty();
    }

    @Test
    void languageAndMetadataAreParameterised_neverInterpolated() {
        List<Object> args = new ArrayList<>();
        String sql = SearchRepository.filterSql(new SearchRepository.Filter("te'; DROP TABLE chunks; --", "{\"topic\":\"ml\"}"), args);
        assertThat(sql).isEqualTo(" AND d.language = ? AND d.metadata @> ?::jsonb");
        assertThat(sql).doesNotContain("DROP");                       // the payload lives in args, not SQL
        assertThat(args).containsExactly("te'; DROP TABLE chunks; --", "{\"topic\":\"ml\"}");
    }

    @Test
    void filterParamsBecomeJsonObject() {
        assertThat(SearchController.toMetadataJson(List.of("topic:ml", "year:2024")))
                .isEqualTo("{\"topic\":\"ml\",\"year\":\"2024\"}");
        assertThat(SearchController.toMetadataJson(List.of("url:https://x.y/z")))   // value may contain ':'
                .isEqualTo("{\"url\":\"https://x.y/z\"}");
        assertThat(SearchController.toMetadataJson(null)).isNull();
        assertThat(SearchController.toMetadataJson(List.of())).isNull();
    }

    @Test
    void malformedFilterIs400() {
        assertThatThrownBy(() -> SearchController.toMetadataJson(List.of("topic")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SearchController.toMetadataJson(List.of(":ml")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SearchController.toMetadataJson(List.of("topic:")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
