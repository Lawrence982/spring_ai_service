package ru.home.vibo.spring_ai_service.advisors.rag;

import com.github.pemistahl.lingua.api.Language;
import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
import lombok.Builder;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Builder
public class BM25RerankEngine {

    private static final LanguageDetector languageDetector = LanguageDetectorBuilder
            .fromLanguages(Language.ENGLISH, Language.RUSSIAN)
            .build();

    // Thread-safe for Tomcat thread pool (Lucene ReuseStrategy uses CloseableThreadLocal).
    // WARNING: при миграции на виртуальные потоки (Project Loom) пересмотреть стратегию кеширования —
    // CloseableThreadLocal ресурсы не освобождаются, т.к. close() не вызывается на статических экземплярах.
    private static final Map<Language, Analyzer> ANALYZER_CACHE = Map.of(
            Language.ENGLISH, new EnglishAnalyzer(),
            Language.RUSSIAN, new RussianAnalyzer()
    );
    private static final Analyzer DEFAULT_ANALYZER = ANALYZER_CACHE.get(Language.ENGLISH);

    // BM25 parameters
    @Builder.Default
    private final double K = 1.2;
    @Builder.Default
    private final double B = 0.75;

    public List<Document> rerank(List<Document> corpus, String query, int limit) {

        if (corpus == null || corpus.isEmpty()) {
            return new ArrayList<>();
        }

        // Compute corpus statistics
        CorpusStats stats = computeCorpusStats(corpus);

        // Tokenize query
        List<String> queryTerms = tokenize(query);

        // Score and sort documents
        return corpus.stream()
                .sorted((d1, d2) -> Double.compare(
                        score(queryTerms, d2, stats),
                        score(queryTerms, d1, stats)
                ))
                .limit(limit)
                .collect(Collectors.toList());
    }

    private CorpusStats computeCorpusStats(List<Document> corpus) {
        Map<String, Integer> docFreq = new HashMap<>();
        Map<Document, List<String>> tokenizedDocs = new HashMap<>();
        int totalLength = 0;
        int totalDocs = corpus.size();

        // Process each document
        for (Document doc : corpus) {
            List<String> tokens = tokenize(doc.getText());
            tokenizedDocs.put(doc, tokens);
            totalLength += tokens.size();

            // Update document frequencies
            Set<String> uniqueTerms = new HashSet<>(tokens);
            for (String term : uniqueTerms) {
                docFreq.put(term, docFreq.getOrDefault(term, 0) + 1);
            }
        }

        double avgDocLength = (double) totalLength / totalDocs;

        return new CorpusStats(docFreq, tokenizedDocs, avgDocLength, totalDocs);
    }

    private double score(List<String> queryTerms, Document doc, CorpusStats stats) {
        List<String> tokens = stats.tokenizedDocs.get(doc);
        if (tokens == null) {
            return 0.0;
        }

        // Calculate term frequencies for this document
        Map<String, Integer> tfMap = new HashMap<>();
        for (String token : tokens) {
            tfMap.put(token, tfMap.getOrDefault(token, 0) + 1);
        }

        int docLength = tokens.size();
        double score = 0.0;

        // Calculate BM25 score
        for (String term : queryTerms) {
            int tf = tfMap.getOrDefault(term, 0); //просто его count - то есть этого влияет на его вес в документе
            int df = stats.docFreq.getOrDefault(term, 1);

            // BM25 IDF calculation редкость слова - оно поднимает
            double idf = Math.log(1 + (stats.totalDocs - df + 0.5) / (df + 0.5));

            // BM25 term score calculation
            double numerator = tf * (K + 1);
            double denominator = tf + K * (1 - B + B * docLength / stats.avgDocLength);
            score += idf * (numerator / denominator);
        }

        return score;
    }

    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        Analyzer analyzer = resolveAnalyzer(text);

        try (TokenStream stream = analyzer.tokenStream(null, text)) {
            stream.reset();
            while (stream.incrementToken()) {
                tokens.add(stream.getAttribute(CharTermAttribute.class).toString());
            }
            stream.end();
        } catch (IOException e) {
            throw new RuntimeException("Tokenization failed", e);
        }

        return tokens;
    }

    private Analyzer resolveAnalyzer(String text) {
        Language lang = languageDetector.detectLanguageOf(text);
        return ANALYZER_CACHE.getOrDefault(lang, DEFAULT_ANALYZER);
    }

    // Inner class to hold corpus statistics
    private static class CorpusStats {
        final Map<String, Integer> docFreq; // token to count
        final Map<Document, List<String>> tokenizedDocs;
        final double avgDocLength;
        final int totalDocs;

        CorpusStats(Map<String, Integer> docFreq,
                    Map<Document, List<String>> tokenizedDocs,
                    double avgDocLength,
                    int totalDocs) {
            this.docFreq = docFreq;
            this.tokenizedDocs = tokenizedDocs;
            this.avgDocLength = avgDocLength;
            this.totalDocs = totalDocs;
        }
    }

}
