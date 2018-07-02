package io.anserini.util;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

import io.anserini.index.IndexUtils;
import io.anserini.index.generator.LuceneDocumentGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.store.FSDirectory;

import org.jsoup.Jsoup;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.ParserProperties;


public class DumpRawDocs {
  private static final Logger LOG = LogManager.getLogger(DumpRawDocs.class);
  private final IndexUtils util;
  private final FSDirectory directory;
  private final DirectoryReader reader;
  private Map<Term, Integer> docFreq;

  public static class Args {
    @Option(name = "-indexPath", metaVar = "[Path]", required = true, usage = "Directory contains index files")
    String indexPath;

    @Option(name = "-docIdName", metaVar = "[String]", required = false, usage = "Field Name of document ID")
    String docIdName = LuceneDocumentGenerator.FIELD_ID;

    @Option(name = "-filter", metaVar = "[Path]", required = false, usage = "Docid filter")
    String filterPath = null;

    @Option(name = "-output", metaVar = "[String]", required = true, usage = "Output Path")
    String output;

    @Option(name = "-transformer", metaVar = "[boolean]", required = false, usage = "use Jsoup transformer")
    boolean transformer = false;
  }

  public class IDNameException extends Exception {
    public IDNameException(String message) {
      super(message);
    }
  }

  public DumpRawDocs(String indexPath) throws IOException {
    this.util = new IndexUtils(indexPath);
    this.directory = FSDirectory.open(new File(indexPath).toPath());
    this.reader = DirectoryReader.open(directory);
    this.docFreq = new HashMap<>();
  }

  private Set<String> getFilteredDocid(String filePath) throws IOException {
    Set<String> docids = new HashSet<>();

    BufferedReader br = new BufferedReader(new FileReader(filePath));

    String line;
    String[] arr;
    while ((line = br.readLine()) != null) {
      arr = line.split("[\\s\\t]+");
      String docid = arr[2];
      docids.add(docid);
    }

    return docids;
  }

  private void writeDocs(String docIdName, String output, Set<String> filteredDocid, boolean transformer)
          throws IOException, DumpRawDocs.IDNameException, IndexUtils.NotStoredException {
    FileWriter fw = new FileWriter(new File(output));
    BufferedWriter bw = new BufferedWriter(fw);

    int len = reader.numDocs();
    if (len > 0) {
      String docName = reader.document(0).get(docIdName);
      if (docName == null) {
        LOG.info(docIdName + " is a wrong document ID field name!");
        bw.close();
        throw new DumpRawDocs.IDNameException(docIdName + " is a wrong document ID field name!");
      }
    } else {
      bw.close();
      throw new DumpRawDocs.IDNameException("No document is in the index!");
    }

    for (int i = 0; i < len; i++) {
      Document d = reader.document(i);
      String docid = d.get(docIdName);

      if (filteredDocid == null || filteredDocid.contains(docid)) {
        IndexableField doc = d.getField(LuceneDocumentGenerator.FIELD_RAW);
        if (doc == null) {
          throw new IndexUtils.NotStoredException("Raw documents not stored!");
        }

        String rawDoc = doc.stringValue();
        if (transformer) {
          rawDoc = Jsoup.parse(rawDoc).text();
        }

        bw.write("<doc> " + docid + "\n");
        bw.write(rawDoc + "\n");
        bw.write("</doc>\n");
      }
    }
  }

  public static void main(String[] args) throws IOException {
    DumpRawDocs.Args indexArgs = new DumpRawDocs.Args();
    CmdLineParser parser = new CmdLineParser(indexArgs, ParserProperties
            .defaults().withUsageWidth(90));

    try {
      parser.parseArgument(args);
    } catch (CmdLineException e) {
      LOG.error(e.getMessage());
      parser.printUsage(System.err);
      return;
    }

    final DumpRawDocs rawDocs = new DumpRawDocs(indexArgs.indexPath);
    try {
      Set<String> filteredDocid = rawDocs.getFilteredDocid(indexArgs.filterPath);
      rawDocs.writeDocs(indexArgs.docIdName, indexArgs.output, filteredDocid, indexArgs.transformer);
    } catch (Exception e) {
      LOG.error(e.getMessage());
    }
  }

}
