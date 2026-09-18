package dev.bhavya.anvesh.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRepositoryTest {

    @Test
    void joinsTermsWithOr() {
        assertThat(SearchRepository.toOrQuery("model accuracy drop"))
                .isEqualTo("'model' | 'accuracy' | 'drop'");
    }

    @Test
    void lowercasesAndStripsPunctuation() {
        assertThat(SearchRepository.toOrQuery("Why, does it DROP?!"))
                .isEqualTo("'why' | 'does' | 'it' | 'drop'");
    }

    @Test
    void keepsTeluguIntact() {
        assertThat(SearchRepository.toOrQuery("విద్యుత్ డిమాండ్"))
                .isEqualTo("'విద్యుత్' | 'డిమాండ్'");
    }

    @Test
    void neutralisesTsquerySyntax() {
        // & | ! ( ) : * are tsquery operators — they must not survive as operators.
        assertThat(SearchRepository.toOrQuery("a & b | !c (d:e*)"))
                .isEqualTo("'a' | 'b' | 'c' | 'd' | 'e'");
    }

    @Test
    void emptyAndNullAreSafe() {
        assertThat(SearchRepository.toOrQuery("")).isEmpty();
        assertThat(SearchRepository.toOrQuery("  ...  ")).isEmpty();
        assertThat(SearchRepository.toOrQuery(null)).isEmpty();
    }
}
