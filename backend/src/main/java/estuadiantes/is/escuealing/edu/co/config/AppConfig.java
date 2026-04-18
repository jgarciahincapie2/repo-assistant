package estuadiantes.is.escuealing.edu.co.config;

import estuadiantes.is.escuealing.edu.co.EmbeddingServiceOllama;
import estuadiantes.is.escuealing.edu.co.LLMServiceOllama;
import estuadiantes.is.escuealing.edu.co.QdrantClient;
import estuadiantes.is.escuealing.edu.co.Retriever;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Value("${app.ollama.url}")
    private String ollamaUrl;

    @Value("${app.ollama.embed-model}")
    private String embedModel;

    @Value("${app.ollama.llm-model}")
    private String llmModel;

    @Value("${app.qdrant.url}")
    private String qdrantUrl;

    @Value("${app.qdrant.collection}")
    private String qdrantCollection;

    @Bean
    public EmbeddingServiceOllama embeddingService() {
        return new EmbeddingServiceOllama(ollamaUrl, embedModel);
    }

    @Bean
    public QdrantClient qdrantClient() throws Exception {
        QdrantClient client = new QdrantClient(qdrantUrl, qdrantCollection);
        client.createCollectionIfNotExists();
        return client;
    }

    @Bean
    public LLMServiceOllama llmService() {
        return new LLMServiceOllama(ollamaUrl, llmModel);
    }

    @Bean
    public Retriever retriever(EmbeddingServiceOllama embeddingService, QdrantClient qdrantClient) {
        return new Retriever(embeddingService, qdrantClient);
    }
}
