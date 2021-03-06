package com.db.table;

import com.db.BplusTreeLongToLong;
import com.db.BplusTreeStringToString20;
import com.db.bplustree.BPlusTreeFile;
import com.util.BufferedRandomAccessFile;
import com.util.PositionManager;
import com.util.StringToLong;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by iceke on 16/7/18.
 */
public class GoodOrderTable {
    private BplusTreeLongToLong goodOrderRecords;
    private String indexFile;
    private BufferedRandomAccessFile randomAccessFile = null;
    private static int BLOCK_SIZE = 5240;
    private BufferedRandomAccessFile positionFile = null;
    private FileChannel randomAccessFileChannel = null;
    private FileChannel positionFileChannel = null;
    private int mappedByteBufferSize = Integer.MAX_VALUE - 200000;

    private Map<Long, List<Long>> indexMap = null;

    private boolean isMaped = false;
    private boolean isPositionMaped = false;
    private List<MappedByteBuffer> mappedByteBuffers = null;
    private MappedByteBuffer positionMappedByteBuffer = null;


    public GoodOrderTable(String indexFile) {
        this.indexFile = indexFile;
        goodOrderRecords = new BplusTreeLongToLong(this.indexFile, BLOCK_SIZE);
        indexMap = new HashMap<>();
    }


    public BufferedRandomAccessFile getRandomAccessFile() {
        return this.randomAccessFile;

    }

    public void setRandomAccessFile(BufferedRandomAccessFile randomAccessFile) {
        this.randomAccessFile = randomAccessFile;
    }

    public BufferedRandomAccessFile getPositionFile() {
        return this.positionFile;
    }

    public void setPositionFile(BufferedRandomAccessFile positionFile) {
        this.positionFile = positionFile;
    }

    public FileChannel getRandomAccessFileChannel() {
        return this.randomAccessFileChannel;
    }

    public void setRandomAccessFileChannel(FileChannel randomAccessFileChannel) {
        this.randomAccessFileChannel = randomAccessFileChannel;
    }

    public FileChannel getPositionFileChannel() {
        return this.positionFileChannel;
    }

    public void setPositionFileChannel(FileChannel positionFileChannel) {
        this.positionFileChannel = positionFileChannel;
    }

    public List<MappedByteBuffer> getMappedByteBuffer(){
        return this.mappedByteBuffers;
    }
    public MappedByteBuffer getPositionMappedByteBuffer(){
        return this.positionMappedByteBuffer;
    }

    public void setMappedByteBuffer(List<MappedByteBuffer> mappedByteBuffers){
        this.mappedByteBuffers = mappedByteBuffers;
    }

    public void setPositionMappedByteBuffer(MappedByteBuffer positionMappedByteBuffer){
        this.positionMappedByteBuffer = positionMappedByteBuffer;
    }

    public void setIndexFile(String indexFile) {
        this.indexFile = indexFile;
    }

    public String getIndexFile() {
        return this.indexFile;
    }

    public void load(File file) throws Exception {
        if (!file.exists()) {
            System.out.println(file + "not found!");
            return;
        }
        System.out.println("Loading File....");

        BufferedRandomAccessFile randomAccessFile = new BufferedRandomAccessFile(file, "r");
        this.randomAccessFile = randomAccessFile;
        this.positionFile = new BufferedRandomAccessFile(indexFile + ".position", "rw");



        if (new File(indexFile).length() > BLOCK_SIZE) {
            System.out.println("Tree found on disk. No need to reconstruct");
            return;
        }

        int i = 0;
        String line = null;
        String[] pair = null;
        int j = 0;
        while (true) {
            long goodPosition = randomAccessFile.getFilePointer();
            line = randomAccessFile.readLine();
            if (line == null) {
                break;
            }
            pair = line.split("\t");
            String goodId = null;
            for(String item:pair){
                if(item.split(":")[0].equals("goodid")){
                    goodId = item.split(":")[1];
                    break;
                }
            }
            // System.out.println(goodId+";"+goodPosition);

            long goodHash = StringToLong.hash(goodId);
            int length = line.length();
            long positionRecord = PositionManager.makeRecord(goodPosition, length);

            //goodOrderRecords.put(StringToLong.hash(goodId), goodPosition);
            if (indexMap.containsKey(goodHash)) {
                List<Long> oldValue = indexMap.get(goodHash);
                //String newValue = oldValue + "#" + positionRecord;
                oldValue.add(positionRecord);
                indexMap.put(goodHash, oldValue);
            } else {
                List<Long> positions = new ArrayList<>();
                positions.add(positionRecord);
                indexMap.put(goodHash, positions);
            }

            i++;

        }
        System.out.println("总行数:" + i);
        System.out.println("find records:" + j);

        System.out.println("Loading Done");
    }

    public void flush() throws IOException {
        //写入B+树 同时将位置写入文件
        long position = 0;
        StringBuilder sb = new StringBuilder();
        int lines = 0;
        System.out.println("GoodOrderTable flush indexMap size is "+indexMap.size());
        for (Map.Entry item : indexMap.entrySet()) {
            //String value = (String) item.getValue() + "#";
            List<Long> positions = (List<Long>)item.getValue();
            for(long tempPosition:positions){
                sb.append(tempPosition).append("#");
            }
            String value = sb.toString();
            int length = value.length();
            if (length > 8000000) {
                throw new IOException("flush: value's length bigger than 8000000");
            }
            long record = PositionManager.makeRecord(position, length);
            positionFile.writeBytes(value);
            position += length;
            goodOrderRecords.put((long) item.getKey(), record);
            sb.setLength(0);
            if(lines ==1000000){
                goodOrderRecords.flush();
                goodOrderRecords = new BplusTreeLongToLong(this.indexFile, BLOCK_SIZE);
                lines = 0;
            }
            lines++;
        }
        indexMap.clear();
        goodOrderRecords.flush();

        //定义文件通道
        this.randomAccessFileChannel = this.randomAccessFile.getChannel();
        this.positionFileChannel = this.positionFile.getChannel();

        //生成bytebuffer
        if(positionFile.length()>Integer.MAX_VALUE - 200000){
            throw new IOException("positionFile is bigger than buffersize");
        }
        positionMappedByteBuffer = positionFileChannel.map(FileChannel.MapMode.READ_ONLY,0,positionFile.length());
        isPositionMaped = true;

        //映射buffer
        mappedByteBuffers = new ArrayList<>();
        for(long i =0;i<randomAccessFile.length();i+=mappedByteBufferSize){
            MappedByteBuffer mappedByteBuffer ;
            if(i+mappedByteBufferSize>randomAccessFile.length()){
                mappedByteBuffer = randomAccessFileChannel.map(FileChannel.MapMode.READ_ONLY, i, randomAccessFile.length()-i);
            }else {
                mappedByteBuffer = randomAccessFileChannel.map(FileChannel.MapMode.READ_ONLY, i,  mappedByteBufferSize);
            }
            mappedByteBuffers.add(mappedByteBuffer);
        }

    }

    public List<String> findOrder(String goodId) throws Exception {

        List<Long> positions = goodOrderRecords.find(StringToLong.hash(goodId));
        List<String> goodValues = new ArrayList<String>();
        //肯定是唯一的
        if (positions == null || positions.size() == 0) {
            return goodValues;
        }
        long record = positions.get(0);
        long offset = PositionManager.getOffset(record);
        int length = PositionManager.getLength(record);
        String goodPositions = null;
       // ByteBuffer buffer = ByteBuffer.allocate(length);
       // positionFileChannel.read(buffer, offset);
        //buffer.flip();
        byte[] positionByte = new byte[length];
        for(int i = (int)offset,j=0;i<(int)offset+length;i++) {
            positionByte[j] = positionMappedByteBuffer.get(i);
            j++;
        }
       // buffer.get(positionByte);
        goodPositions = new String(positionByte);



        for (String goodPosition : goodPositions.split("#")) {
            long positionRecord = Long.parseLong(goodPosition);
            int orderLength = PositionManager.getLength(positionRecord);
            long orderOffset = PositionManager.getOffset(positionRecord);

            String goodValue = null;
            //ByteBuffer orderBuffer = ByteBuffer.allocate(orderLength);
            //randomAccessFileChannel.read(orderBuffer, orderOffset);

            //orderBuffer.flip();


            byte[] orderByte = new byte[orderLength];
            int index = (int)(orderOffset/(long)mappedByteBufferSize);
            MappedByteBuffer nowMappedByteBuffer = mappedByteBuffers.get(index);


            int realOffset = (int)(orderOffset - index*mappedByteBufferSize);

            //一条记录跨段
            if(realOffset+orderLength>mappedByteBufferSize){
                MappedByteBuffer nextMappedByteBuffer = mappedByteBuffers.get(index+1);
                int j = 0;
                int m = 0;
                for(int i = realOffset;i<mappedByteBufferSize;i++){
                    orderByte[j] = nowMappedByteBuffer.get(i);
                    j++;
                }

                for(int i = 0;j<orderLength;j++,i++){
                    orderByte[j] = nextMappedByteBuffer.get(i);
                }

            }else {//不跨段
                for (int i = realOffset, j = 0; i < realOffset + orderLength; i++) {
                    orderByte[j] = nowMappedByteBuffer.get(i);
                    j++;
                }
            }
            goodValue = new String(orderByte);
            goodValues.add(goodValue);

        }
        return goodValues;

    }
}
