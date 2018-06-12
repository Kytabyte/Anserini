package io.anserini.util;

import java.io.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

import io.anserini.search.query.TopicReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.ParserProperties;


public class TopicTokenizer {

    private static final Logger LOG = LogManager.getLogger(TopicTokenizer.class);

    public static class Args {
        @Option(name = "-input", metaVar = "[Path]", required = true, usage = "topic file")
        String input;

        @Option(name = "-topicreader", required = true, usage = "define how to read the topic(query) file: one of [Trec|Webxml]")
        public String topicReader;

        @Option(name = "-output", metaVar = "[Path]", required = true, usage = "output path")
        String output;

        @Option(name = "-keepstopwords", required = false, usage = "Boolean switch to keep stopwords in the query topics")
        public boolean keepstop = false;

        @Option(name = "-topicfield", usage = "Which field of the query should be used, default \"title\"." +
                " For TREC Adhoc topics, descripion or narrative can be used.")
        public String topicfield = "title";
    }

    public void writeTokenizedWord(SortedMap<Integer, Map<String, String>> topics,
                                   Analyzer analyzer, String output, String topicField) throws IOException {
        FileWriter fw = new FileWriter(new File(output));
        BufferedWriter bw = new BufferedWriter(fw);

        for (Map.Entry<Integer, Map<String, String>> entry : topics.entrySet()) {
            int qID = entry.getKey();
            String content = entry.getValue().get(topicField);
            List<String> tokenizedWords = AnalyzerUtils.tokenize(analyzer, content);
            bw.write(String.valueOf(qID) + '\n');
            for (String word : tokenizedWords) {
                bw.write(word + " ");
            }
            bw.write("\n\n");
        }

        bw.close();
    }

    public static void main(String[] args) {
        TopicTokenizer.Args ttArgs = new TopicTokenizer.Args();
        CmdLineParser parser = new CmdLineParser(ttArgs, ParserProperties
                .defaults().withUsageWidth(90));
        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            LOG.error(e.getMessage());
            parser.printUsage(System.err);
            return;
        }
        final TopicTokenizer tt = new TopicTokenizer();
        try {
            TopicReader tr = (TopicReader)Class.forName("io.anserini.search.query."+ttArgs.topicReader+"TopicReader")
                    .getConstructor(Path.class).newInstance(ttArgs.input);
            SortedMap<Integer, Map<String, String>> topics = tr.read();
            Analyzer analyzer = ttArgs.keepstop ? new EnglishAnalyzer(CharArraySet.EMPTY_SET) : new EnglishAnalyzer();

            tt.writeTokenizedWord(topics, analyzer, ttArgs.output, ttArgs.topicfield);
        } catch (Exception e) {
            LOG.error(e.getMessage());
        }
    }
}
