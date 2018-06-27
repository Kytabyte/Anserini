package io.anserini.util;

import java.io.File;
import java.io.IOException;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;

import io.anserini.index.IndexUtils;
import io.anserini.index.generator.LuceneDocumentGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.FSDirectory;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.ParserProperties;


public class DumpTfidfVectors {
    private static final Logger LOG = LogManager.getLogger(DumpTfidfVectors.class);
    private final IndexUtils util;
    private final FSDirectory directory;
    private final DirectoryReader reader;
    private Map<Term, Integer> docFreq;

    public static class Args {
        @Option(name = "-indexPath", metaVar = "[Path]", required = true, usage = "Directory contains index files")
        String indexPath;

        @Option(name = "-docIdName", metaVar = "[String]", required = false, usage = "Field Name of document ID")
        String docIdName = LuceneDocumentGenerator.FIELD_ID;

        @Option(name = "-dropDfByNum", metaVar = "[int]", required = false, usage = "drop df less than given number")
        int dfThreshold = 0;

        @Option(name = "-dropDfByRatio", metaVar = "[float]", required = false, usage = "drop df less than given ratio")
        float dfRatioThreshold = 0f;

        @Option(name = "-output", metaVar = "[String]", required = true, usage = "Output Path")
        String output;
    }

    public class IDNameException extends Exception {
        public IDNameException(String message) {
            super(message);
        }
    }

    public DumpTfidfVectors(String indexPath) throws IOException {
        this.util = new IndexUtils(indexPath);
        this.directory = FSDirectory.open(new File(indexPath).toPath());
        this.reader = DirectoryReader.open(directory);
        this.docFreq = new HashMap<>();
    }

    private int getDocFreq(Term term) {
        if (docFreq.containsKey(term)) {
            return docFreq.get(term);
        }

        int docFreq;
        try {
            docFreq = reader.docFreq(term);
        } catch (Exception e) {
            e.printStackTrace();
            LOG.error("Cannot find term " + term.toString() + " in indexing file.");
            return -1;
        }
        this.docFreq.put(term, docFreq);
        return docFreq;
    }

    private Map<Term, Long> getTermFreq(String docid) throws IOException {
        Map<Term, Long> termFreq = new HashMap<>();

        Terms terms = reader.getTermVector(util.convertDocidToLuceneDocid(docid),
                LuceneDocumentGenerator.FIELD_BODY);
        if (terms == null) {
            return null;
        }
        TermsEnum te = terms.iterator();
        if (te == null) {
            return null;
        }
        while ((te.next()) != null) {
            termFreq.put(new Term(LuceneDocumentGenerator.FIELD_BODY, te.term()), te.totalTermFreq());
        }
        return termFreq;
    }

    private float toTfidf(Term term, long termFreq, int numDocs, int threshold) {
        int docFreq;
        try {
            docFreq = getDocFreq(term);
        } catch (Exception e) {
            docFreq = 0;
        }

        if (docFreq < threshold) {
            return 0f;
        }

        // String tfidf = String.format("%.6f", termFreq * Math.log(numDocs * 1.0 / docFreq));

        return (float) (termFreq * Math.log(numDocs * 1.0 / docFreq));
    }


    /**
     *
     * @param docIdName The field name of doc id stored in Lucene Document.
     *                  Default is LuceneDocumentGenerator.FIELD_ID
     * @param output The output path of the file containing tf-idf vector of each doc
     * @throws IOException that Lucene API throws
     * @throws DumpTfidfVectors.IDNameException when id field name is wrong
     * @throws IndexUtils.NotStoredException when `-storeDocvector` is not enabled while indexing
     *
     * This method will write a file to `output` with the following format:
     *
     * DOC_ID#1
     * TERM#1 TF_IDF#1
     * TERM#2 TF_IDF#2
     * ...
     * TERM#N TF_IDF#N
     * [Empty Line]
     * DOC_ID#2
     * TERM#1 TF_IDF#1
     * TERM#2 TF_IDF#2
     * ...
     *
     */
    public void writeTfidf(String docIdName, String output, int threshold)
            throws IOException, DumpTfidfVectors.IDNameException, IndexUtils.NotStoredException {

        FileWriter fw = new FileWriter(new File(output));
        BufferedWriter bw = new BufferedWriter(fw);

        int len = reader.numDocs();
        int numNonEmptyDocs = reader.getDocCount(LuceneDocumentGenerator.FIELD_BODY);
        if (len > 0) {
            String docName = reader.document(0).get(docIdName);
            if (docName == null) {
                LOG.info(docIdName + " is a wrong document ID field name!");
                bw.close();
                throw new DumpTfidfVectors.IDNameException(docIdName + " is a wrong document ID field name!");
            }
        }
        else {
            bw.close();
            throw new DumpTfidfVectors.IDNameException("No document is in the index!");
        }

        for (int i = 0; i < len; i++) {
            String docid = reader.document(i).get(docIdName);

            Map<Term, Long> docVector = getTermFreq(docid);

            Term docKey;
            long docValue;
            float tfidf;

            if (docVector == null) {
                throw new IndexUtils.NotStoredException("Document vector not stored!");
            } else if (docVector.size() == 0) {
                LOG.warn("Empty document with id " + docid);
            } else {
                bw.write(docid + "\n");
                for (Map.Entry<Term, Long> entry : docVector.entrySet()) {
                    docKey = entry.getKey();
                    docValue = entry.getValue();

                    tfidf = toTfidf(docKey, docValue, numNonEmptyDocs, threshold);
                    if (tfidf == 0) {
                        // LOG.info("Dropped term " + docKey + " in index.");
                    } else {
                        bw.write(docKey.bytes().utf8ToString() + " " + String.format("%.6f", tfidf) + "\n");
                    }
                }
                bw.write("\n");
            }


            if ((i % 100000) == 0) {
                LOG.info("DumpDocids: " + i + " docs got");
            }
        }
        bw.close();
    }

    private int getDropDfThreshold(int numThreshold, float ratioThreshold) throws IOException {

        if (numThreshold > 0) {
            return numThreshold;
        }

        if (ratioThreshold > 0) {
            int numNonEmptyDocs = reader.getDocCount(LuceneDocumentGenerator.FIELD_BODY);
            int threshold = (int) (numNonEmptyDocs * ratioThreshold);

            return threshold;
        }

        return 0;

    }

    public static void main(String[] args) throws IOException {
        DumpTfidfVectors.Args indexArgs = new DumpTfidfVectors.Args();
        CmdLineParser parser = new CmdLineParser(indexArgs, ParserProperties
                .defaults().withUsageWidth(90));

        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            LOG.error(e.getMessage());
            parser.printUsage(System.err);
            return;
        }

        final DumpTfidfVectors tfidfVectors = new DumpTfidfVectors(indexArgs.indexPath);
        try {
            int threshold = tfidfVectors.getDropDfThreshold(indexArgs.dfThreshold, indexArgs.dfRatioThreshold);
            tfidfVectors.writeTfidf(indexArgs.docIdName, indexArgs.output, threshold);
        } catch (Exception e) {
            LOG.error(e.getMessage());
        }
    }

}
