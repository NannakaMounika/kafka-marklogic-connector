package com.marklogic.kafka.connect.source;

import com.marklogic.client.DatabaseClient;
import com.marklogic.client.datamovement.DataMovementManager;
import com.marklogic.client.datamovement.ExportListener;
import com.marklogic.client.datamovement.JobTicket;
import com.marklogic.client.datamovement.QueryBatcher;
import com.marklogic.client.document.DocumentPatchBuilder;
import com.marklogic.client.document.GenericDocumentManager;
import com.marklogic.client.document.TextDocumentManager;
import com.marklogic.client.io.DocumentMetadataHandle;
import com.marklogic.client.io.InputStreamHandle;
import com.marklogic.client.io.SearchHandle;
import com.marklogic.client.io.StringHandle;
import com.marklogic.client.query.MatchDocumentSummary;
import com.marklogic.client.query.QueryManager;
import com.marklogic.client.query.StringQueryDefinition;
import com.marklogic.kafka.connect.DefaultDatabaseClientCreator;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class MarkLogicClientSourceTask extends SourceTask {

    static final Integer MARKLOGIC_POLL_INTERVAL = 5*1000;

    private static final Logger logger = LoggerFactory.getLogger(MarkLogicClientSourceTask.class);

    private Map<String, String> config;
    private String topic;
    private String query;
    String rawCollections;
    String[] collections = null;

    private DatabaseClient databaseClient;
    private QueryManager queryMgr;
    private StringQueryDefinition stringQueryDefinition;

    @Override
    public String version() {
        return MarkLogicSourceConnector.MARKLOGIC_SOURCE_CONNECTOR_VERSION;
    }

    @Override
    public void start(Map<String, String> props) {
        logger.info("MarkLogicSourceTask Starting");

        config = props;
        topic = config.get(MarkLogicSourceConfig.KAFKA_TOPIC);
        query = config.get(MarkLogicSourceConfig.QUERY);
        rawCollections = config.get(MarkLogicSourceConfig.QUERY_COLLECTIONS);

        databaseClient = new DefaultDatabaseClientCreator().createDatabaseClient(config);
        queryMgr = databaseClient.newQueryManager();
        stringQueryDefinition = queryMgr.newStringDefinition();
        stringQueryDefinition.setCriteria(query);
        logger.info("Query: " + query);

        if (rawCollections != null) {
            collections = rawCollections.split(",");
            for (String collection : collections ) {
                logger.info("Query Collection: " + collection);
            }
            stringQueryDefinition.setCollections(collections);
        }

        logger.info("MarkLogicSourceTask Started");
    }

    @Override
    public void stop() { }

    @Override
    public List<SourceRecord> poll() throws InterruptedException {
        sleep();
        logger.info("Querying for MarkLogic records");

        SearchHandle results = queryMgr.search(stringQueryDefinition, new SearchHandle());
        ArrayList<SourceRecord> records = getRecordsFromMatches(results);

        logger.info(String.format("Finished querying MarkLogic (%d records found)", results.getTotalResults()));
        return records;
    }

    public void commitRecord​(SourceRecord record) throws java.lang.InterruptedException {
        String docUri = (String) record.sourcePartition().get("filename");
        TextDocumentManager textDocumentManager = databaseClient.newTextDocumentManager();
        DocumentMetadataHandle metadataHandle = new DocumentMetadataHandle();
        StringHandle stringHandle = new StringHandle();
        String documentContent = textDocumentManager.read(docUri, metadataHandle, stringHandle).get();
        metadataHandle.getCollections().remove(rawCollections.split(",")[0]);
        metadataHandle.getCollections().add("delivered-to-kafka");
        textDocumentManager.writeMetadata(docUri, metadataHandle);
        logger.info(docUri + " committed.");
    }

    private ArrayList<SourceRecord> getRecordsFromMatches(SearchHandle results) {
        TextDocumentManager textDocumentManager = databaseClient.newTextDocumentManager();
        ArrayList<SourceRecord> records = new ArrayList<>();
        MatchDocumentSummary[] summaries = results.getMatchResults();
        Integer offsetCounter = 1;
        for (MatchDocumentSummary summary : summaries ) {
            String docUri = summary.getUri();
            DocumentMetadataHandle metadataHandle = new DocumentMetadataHandle();
            StringHandle stringHandle = new StringHandle();
            String documentContent = textDocumentManager.read(docUri, metadataHandle, stringHandle).get();
            Map<String, String> sourcePartition = Collections.singletonMap("filename", docUri);
            Map<String, Integer> sourceOffset = Collections.singletonMap("position", offsetCounter++);
            records.add(new SourceRecord(sourcePartition, sourceOffset, topic, Schema.STRING_SCHEMA, documentContent));

            logger.info("Exporting document from MarkLogic: " + docUri);
        }
        return records;
    }

    private void sleep() throws InterruptedException {
        Thread.sleep(MARKLOGIC_POLL_INTERVAL);
    }
}