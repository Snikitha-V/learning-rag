package org.example;

public class DbChunk {
    private String chunkId;
    private String title;
    private String text;
    private String chunkType;
    private String metadataJson;

    public String getChunkId() { return chunkId; }
    public void setChunkId(String chunkId) { this.chunkId = chunkId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getChunkType() { return chunkType; }
    public void setChunkType(String chunkType) { this.chunkType = chunkType; }
    public String getMetadata() { return metadataJson; }
    public void setMetadata(String metadataJson) { this.metadataJson = metadataJson; }
}

