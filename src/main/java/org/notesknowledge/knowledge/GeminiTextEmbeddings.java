package org.notesknowledge.knowledge;

import java.util.ArrayList;
import java.util.List;
import com.google.genai.Client;
import com.google.genai.types.EmbedContentConfig;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;

/** Model-specific wire format, shared by document and query adapters, never an authority source. */
final class GeminiTextEmbeddings {
    static final String EMBEDDING_2_FORMAT="document/title-none-text/query/search-result/float32-v1";
    private final Client client;
    private final AiDerivationProperties config;
    private final boolean query;
    private final GoogleGenAiTextEmbeddingOptions options;
    private final GoogleGenAiTextEmbeddingModel legacy;

    GeminiTextEmbeddings(Client client,AiDerivationProperties config,boolean query) {
        this.client=client;this.config=config;this.query=query;
        options=client==null||embedding2(config)?null:GoogleGenAiTextEmbeddingOptions.builder()
            .model(config.embeddingModel()).dimensions(config.dimension())
            .taskType(query?GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_QUERY:GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_DOCUMENT)
            .autoTruncate(false).build();
        legacy=options==null?null:new GoogleGenAiTextEmbeddingModel(
            GoogleGenAiEmbeddingConnectionDetails.builder().genAiClient(client).build(),options,GoogleDerivationAdapters.noHiddenRetry());
    }

    static boolean embedding2(AiDerivationProperties config) {
        return "gemini".equals(config.provider())&&"gemini-embedding-2".equals(config.embeddingModel());
    }
    boolean available(){return client!=null;}

    List<float[]> embed(List<String> texts,Runnable revalidate) {
        if(!available())throw new DerivationFailure(KnowledgeWork.Failure.PROVIDER_UNAVAILABLE);
        if(texts==null||texts.isEmpty()||texts.size()>16||texts.stream().anyMatch(t->t==null||t.isBlank()||t.length()>12000))
            throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED);
        try {
            List<float[]> vectors;
            if(embedding2(config)) {
                vectors=new ArrayList<>();
                // Embedding 2 aggregates multi-part inputs. Separate requests preserve one vector per chunk.
                // The old taskType field is unsupported; both task prefixes are part of the new lineage.
                for(String text:texts) {
                    revalidate.run();
                    String input=query?"task: search result | query: "+text:"title: none | text: "+text;
                    // Conservative byte bound below the 8192-token context; never rely on silent truncation.
                    if(input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>8000)
                        throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED);
                    var response=client.models.embedContent(config.embeddingModel(),input,
                        EmbedContentConfig.builder().outputDimensionality(config.dimension()).build());
                    var result=response.embeddings().orElseThrow(()->new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT));
                    if(result.size()!=1)throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
                    var values=result.getFirst().values().orElseThrow(()->new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT));
                    float[] vector=new float[values.size()];
                    for(int i=0;i<vector.length;i++) {
                        if(values.get(i)==null)throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
                        vector[i]=values.get(i);
                    }
                    vectors.add(vector);
                }
            } else {
                revalidate.run();
                vectors=legacy.call(new EmbeddingRequest(texts,options)).getResults().stream().map(r->r.getOutput()).toList();
            }
            if(vectors.size()!=texts.size())throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
            for(float[] vector:vectors)validate(vector,config.dimension());
            return List.copyOf(vectors);
        } catch(DerivationFailure failure){throw failure;}
        catch(RuntimeException failure){throw GoogleDerivationAdapters.providerFailure(failure);}
    }

    private static void validate(float[] vector,int dimension) {
        if(vector==null||vector.length!=dimension)throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
        double norm=0;
        for(float value:vector){if(!Float.isFinite(value))throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);norm+=(double)value*value;}
        if(norm==0)throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
    }
}
