package dev.bhavya.anvesh.embedding;

import dev.bhavya.anvesh.config.AnveshProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

    @Bean(destroyMethod = "")
    public EmbeddingService embeddingService(AnveshProperties props) {
        var cfg = props.embedding();
        return switch (cfg.provider()) {
            case "onnx" -> new OnnxEmbeddingService(
                    Path.of(cfg.onnx().modelPath()),
                    Path.of(cfg.onnx().tokenizerPath()),
                    cfg.onnx().maxTokens(),
                    cfg.dimension());
            case "hash" -> {
                log.warn("Using HASH embedding provider — fine for dev/tests, NOT semantic. Set EMBEDDING_PROVIDER=onnx for real search.");
                yield new HashEmbeddingService(cfg.dimension());
            }
            default -> throw new IllegalArgumentException("Unknown embedding provider: " + cfg.provider());
        };
    }
}
