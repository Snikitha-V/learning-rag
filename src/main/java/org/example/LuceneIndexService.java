package org.example;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.*;
import org.apache.lucene.search.*;
import org.apache.lucene.queryparser.classic.QueryParser;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class LuceneIndexService {
    private final Path indexDir;
    private final StandardAnalyzer analyzer = new StandardAnalyzer();

    public LuceneIndexService(String indexDirPath) {
        this.indexDir = Paths.get(indexDirPath);
    }

    public void rebuildIndex(List<DbChunk> chunks) throws IOException {
        Directory dir = FSDirectory.open(indexDir);
        IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
        iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        try (IndexWriter writer = new IndexWriter(dir, iwc)) {
            for (DbChunk c : chunks) {
                Document doc = new Document();
                doc.add(new StringField("chunk_id", c.getChunkId(), Field.Store.YES));
                doc.add(new TextField("title", c.getTitle()==null ? "" : c.getTitle(), Field.Store.YES));
                doc.add(new TextField("text", c.getText()==null ? "" : c.getText(), Field.Store.YES));
                writer.addDocument(doc);
            }
            writer.commit();
        }
    }

    public List<String> search(String queryString, int topK) throws Exception {
        Directory dir = FSDirectory.open(indexDir);
        try (DirectoryReader reader = DirectoryReader.open(dir)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            QueryParser parser = new QueryParser("text", analyzer);
            String escaped = QueryParser.escape(queryString);
            Query q = parser.parse(escaped);
            TopDocs hits = searcher.search(q, topK);
            List<String> ids = new ArrayList<>();
            for (ScoreDoc sd : hits.scoreDocs) {
                Document doc = searcher.doc(sd.doc);
                ids.add(doc.get("chunk_id"));
            }
            return ids;
        }
    }
}
