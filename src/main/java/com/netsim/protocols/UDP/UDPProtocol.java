package com.netsim.protocols.UDP;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.netsim.addresses.Port;

import com.netsim.networkstack.Protocol;

public class UDPProtocol implements Protocol {
        private final Port sourcePort;
        private final Port destinationPort;
        private final int MSS; // maximum segment size

        private Protocol nextProtocol;
        private Protocol previousProtocol;

        /**
         * @param MSS the maximum segment size (calculated from the MTU of the device)
         * @param source the source port of the message
         * @param destination the destination port of the message
         * @throws IllegalArgumentException when MSS <= 0 and when source or destination is null
         */
        public UDPProtocol(int MSS, Port source, Port destination) throws IllegalArgumentException {
            if(MSS <= 0)
                throw new IllegalArgumentException("UDP: segment size must be positive");

            if(source == null || destination == null)
                throw new IllegalArgumentException("UDP: source and destination port cannot be null");

            this.MSS = MSS;
            this.sourcePort = source;
            this.destinationPort = destination;

            this.nextProtocol = null;
            this.previousProtocol = null;
        }

        /**
         * @return the source port attribute
         */
        public Port getSourcePort() {
                return this.sourcePort;
        }

        /**
         * @return the destination port attribute
         */
        public Port getDestinationPort() {
                return this.destinationPort;
        }

        /**
         * @return the maximum segment size (MSS)
         */
        public int getMSS() {
                return this.MSS;
        }

        /**
         * Encapsulating message in a raw byte array adding 
         * UDP header
         * @throws IllegalArgumentException if upperLayerPDU is null or its length is 0
         * @throws NullPointerException if the next protocol of UDP is not defined
         * @throws cdRuntimException if something goes wrong when encapsulating 
         */
        public byte[] encapsulate(byte[] upperLayerPDU) throws IllegalArgumentException, NullPointerException, RuntimeException {
            if(upperLayerPDU == null || upperLayerPDU.length == 0)
                throw new IllegalArgumentException("UDP: payload on encapsulation cannot be null");

            if(this.nextProtocol == null)
                throw new NullPointerException("UDP: next protocol is not defined");

            int sequenceNumber = 0; 
            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            for(int offset = 0; offset < upperLayerPDU.length; offset += this.MSS) {
                int len = Math.min(this.MSS, upperLayerPDU.length - offset);
                byte[] chunk = new byte[len];
                System.arraycopy(upperLayerPDU, offset, chunk, 0, len);

                UDPSegment segment = new UDPSegment(
                    this.sourcePort,
                    this.destinationPort,
                    sequenceNumber,
                    chunk
                );
                
                sequenceNumber++;
                try {
                    baos.write(segment.toByte());
                } catch(IOException e) {
                    throw new RuntimeException("UDP: I/O Exception while encapsulating; " + e);
                }
            }   

            return this.nextProtocol.encapsulate(baos.toByteArray());

        }
        
        /**
         * Parses a contiguous byte stream of back-to-back UDPSegments, using each segment’s
         * built-in 16-bit length field (in bits) to frame them.
         *
         * @param data the raw concatenated segments
         * @return a List of UDPSegment objects
         * @throws IllegalArgumentException if data is null, truncated, or malformed
         */
        private List<UDPSegment> parseSegments(byte[] data) {
            if (data == null) {
                throw new IllegalArgumentException("UDP: input data is null");
            }

            List<UDPSegment> list = new ArrayList<>();
            ByteBuffer bb = ByteBuffer.wrap(data);
            final int HEADER_LEN = 2 /*src*/ + 2 /*dst*/ + 2 /*seq*/ + 2 /*len*/;

            while (bb.remaining() >= HEADER_LEN) {
                // 1) Read just the header
                byte[] header = new byte[HEADER_LEN];
                bb.get(header);

                // 2) Extract the 16-bit length-in-bits field (bytes 6–7)
                ByteBuffer hbuf = ByteBuffer.wrap(header);
                hbuf.position(6);
                short lengthBits = hbuf.getShort();
                if (lengthBits < HEADER_LEN * Byte.SIZE) {
                    throw new IllegalArgumentException(
                        "UDP: invalid segment length (too small): " + lengthBits);
                }
                if (lengthBits % Byte.SIZE != 0) {
                    throw new IllegalArgumentException(
                        "UDP: segment length not a multiple of 8: " + lengthBits);
                }

                // 3) Compute how many bytes the full segment occupies
                int totalBytes = lengthBits / Byte.SIZE;
                int payloadBytes = totalBytes - HEADER_LEN;
                if (payloadBytes > bb.remaining()) {
                    throw new IllegalArgumentException(
                        "UDP: truncated segment, expected payload=" + payloadBytes
                        + " bytes but only " + bb.remaining() + " remain");
                }

                // 4) Read the payload
                byte[] payload = new byte[payloadBytes];
                bb.get(payload);

                // 5) Reconstruct the full segment byte array
                byte[] segBytes = new byte[totalBytes];
                System.arraycopy(header, 0, segBytes, 0, HEADER_LEN);
                System.arraycopy(payload, 0, segBytes, HEADER_LEN, payloadBytes);

                // 6) Parse and add to list
                list.add(UDPSegment.fromBytes(segBytes));
            }

            return list;
        }

        /**
         * Decapsulating message in a raw byte array 
         * removing UDP header
         * @throws IllegalArgumentException if lowerLayerPDU is null or its length is 0
         * @throws NullPointerException if previous protocol of UDP is not defined
         */
        public byte[] decapsulate(byte[] lowerLayerPDU) throws IllegalArgumentException, NullPointerException {
            if(lowerLayerPDU == null || lowerLayerPDU.length == 0)
                throw new IllegalArgumentException("UDP: decapsulation received null or empty segments data");

            if(this.previousProtocol == null)
                throw new NullPointerException("UDP: previous protocol not defined");


            // 1) Parsiamo i segmenti "grezzi" in oggetti UDPSegment
            List<UDPSegment> segments = this.parseSegments(lowerLayerPDU);

            // 2) Sort by sequence number
            segments.sort(Comparator.comparingInt(UDPSegment::getSequenceNumber));

            // 3) Estrai e concatena i payload
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (UDPSegment seg : segments) {
                byte[] raw = seg.toByte();      // header + payload
                byte[] header = seg.getHeader();
                int hdrLen = header.length;
                baos.write(raw, hdrLen, raw.length - hdrLen);
            }

            return this.previousProtocol.decapsulate(baos.toByteArray());
        }


        public void setNext(Protocol nextProtocol) throws NullPointerException {
                if(nextProtocol == null)
                    throw new NullPointerException("UDP: next protocol cannot be null");

                this.nextProtocol = nextProtocol;
        }

        public void setPrevious(Protocol previousProtocol) throws NullPointerException {
                if(previousProtocol == null)
                    throw new NullPointerException("UDP: previous protocol cannot be null");
                
                this.previousProtocol = previousProtocol;
        }  

        public Port getSource() {
            return this.sourcePort;
        }

        public Port getDestination() {
            return this.destinationPort;
        }

        /**
         * Extracts the source port from a single UDP segment byte array.
         *
         * @param segment a raw byte array containing one complete UDPSegment (header + payload)
         * @return a Port representing the source port
         * @throws IllegalArgumentException if segment is null or shorter than 4 bytes
         */
        public Port extractSource(byte[] segment) {
            if(segment == null || segment.length < 4)
                throw new IllegalArgumentException("UDP: segment too short to extract source port");

            int srcPortVal = ((segment[0] & 0xFF) << 8) | (segment[1] & 0xFF);
            return new Port(String.valueOf(srcPortVal));
        }

        /**
         * Extracts the destination port from a single UDP segment byte array.
         *
         * @param segment a raw byte array containing one complete UDPSegment (header + payload)
         * @return a Port representing the destination port
         * @throws IllegalArgumentException if segment is null or shorter than 4 bytes
         */
        public Port extractDestination(byte[] segment) {
            if(segment == null || segment.length < 4)
                throw new IllegalArgumentException("UDP: segment too short to extract destination port");

            int dstPortVal = ((segment[2] & 0xFF) << 8) | (segment[3] & 0xFF);
            return new Port(String.valueOf(dstPortVal));
        }

        public UDPProtocol copy() {
            return new UDPProtocol(this.MSS, this.sourcePort, this.destinationPort);
        }
}
