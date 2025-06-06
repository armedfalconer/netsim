package com.netsim.standard.HTTP;

import com.netsim.networkstack.PDU;
import java.nio.charset.StandardCharsets;

public class HTTPRequest extends PDU {
    private final HTTPMethods method;
    private final String path;
    private final String host;
    private final String header;
    private final byte[] content;

    /**
     * @param method  form HTTPMethods
     * @param path e.g. "/index.html"
     * @param host e.g. "www.example.com"
     * @param content body of the request
     * @throws IllegalArgumentException if any of the arguments is null or content.length is 0
     */
    public HTTPRequest(HTTPMethods method, String path, String host, byte[] content) {
        super(null, null);
        if(path == null || host == null || content == null)
            throw new IllegalArgumentException("HTTPRequest: one of the arguments is null");

        this.content = content;
        this.method = method;
        this.path = path;
        this.host = host;
        this.header = this.getStringHeader();
    }

    public byte[] getContent() {
        return this.content;
    }

    private String getStringHeader() {
        StringBuilder sb = new StringBuilder();
        // Request‐line
        sb.append(this.method.name())
          .append(' ')
          .append(this.path)
          .append(" HTTP/1.0")
          .append("\r\n");
        // Host
        sb.append("Host: ")
          .append(this.host)
          .append("\r\n");
        // if method is post lenght is needed
        if(this.method == HTTPMethods.POST) {
            int len = this.content.length;
            sb.append("Content-Length: ")
              .append(len)
              .append("\r\n");
        }
        // End of header
        sb.append("\r\n");

        return sb.toString();
    }

    /**
     * @return header of the request
     */
    public byte[] getHeader() {
        return this.header.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Combine header and body in a single raw byte array
     * @return byte array of header and body combined
     */
    @Override
    public byte[] toByte() {
        byte[] headerBytes = this.getHeader();
        byte[] bodyBytes = this.content;
        byte[] result = new byte[headerBytes.length + bodyBytes.length];
        System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
        System.arraycopy(bodyBytes, 0, result, headerBytes.length, bodyBytes.length);
        return result;
    }
}
