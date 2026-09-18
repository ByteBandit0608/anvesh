package dev.bhavya.anvesh.ingest;

import dev.bhavya.anvesh.config.AnveshProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChunkerConfig {
    @Bean
    public TextChunker textChunker(AnveshProperties props) {
        return new TextChunker(props.chunking().maxChars(), props.chunking().overlapChars());
    }
}
