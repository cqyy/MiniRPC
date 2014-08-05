package com.cqyuanye.ipc;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;


/**
 * Created by Kali on 14-8-4.
 */
class Server {

    private final Queue<Call> callQueue = new LinkedList<>();

    private class Connection{
        private final SocketChannel channel;
        private final LinkedList<Call> calls = new LinkedList<>();

        public Connection(SocketChannel channel){
            this.channel = channel;
        }

        public void send(Call call) throws IOException {
            synchronized (calls){
                calls.addLast(call);
                if (calls.size() == 1){
                    processOneCall();
                }
            }
        }

        private void processOneCall() throws IOException {
            Call call = calls.removeFirst();
            try {
                channel.write(call.response);
                if (call.response.hasRemaining()){
                    calls.addFirst(call);
                }
            } catch (IOException e) {
                throw e;
            }

        }

    }

    private class Call{
        private int index;
        private Class[] paramClass;
        private Object[] params;
        private ByteBuffer response;

        public Call(int index,Object param){
            this.index = index;
            resoveParam(param);
        }

        private void resoveParam(Object param){
            //TODO resove param to classes and object
        }
    }

    private class Listener extends Thread{

        private volatile boolean shouldRun = true;

        private final Selector selector;
        private final Reader[] readers;

        private int nextReaderIndex = 0;

        public Listener(Selector selector){
            this.selector = selector;
            readers = new Reader[10];
            for (int i = 0; i < readers.length; i++){
                try {
                    readers[i] = new Reader(Selector.open());
                    Thread thread = new Thread(readers[i]);
                    thread.setDaemon(true);
                    thread.start();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        private Reader getReader(){
            Reader reader = readers[nextReaderIndex];
            nextReaderIndex = (nextReaderIndex + 1)%readers.length;
            return reader;
        }


        public SelectionKey registerChannel(SelectableChannel channel) throws ClosedChannelException {
            return channel.register(selector, SelectionKey.OP_ACCEPT);
        }

        public void end(){
            shouldRun = false;
            selector.wakeup();
        }

        @Override
        public void run() {
            while (shouldRun){
                try {
                    selector.select(60 * 1000);
                    Set<SelectionKey> keyList = selector.selectedKeys();
                    for (SelectionKey key : keyList){
                        if (key.isValid()){
                            Reader reader = getReader();
                            reader.registerChannel(key.channel());
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        private class Reader implements Runnable{

            private final Selector selector;

            public Reader(Selector selector){
                this.selector = selector;
            }

            public SelectionKey registerChannel(SelectableChannel channel) throws ClosedChannelException {
                return channel.register(selector,SelectionKey.OP_READ);
            }

            @Override
            public void run() {
                while (true){
                    try {
                        selector.select();
                        Set<SelectionKey> keys = selector.selectedKeys();
                        for (SelectionKey key : keys){
                            if (key.isValid()){
                                readOneCall(key.channel());
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                }
            }

            private void readOneCall(Channel channel){
                SocketChannel socketChannel = (SocketChannel)channel;
                ByteBuffer indexBuf = ByteBuffer.allocate(8);       //int * 2
                try {
                    socketChannel.read(indexBuf);
                    if (indexBuf.hasRemaining()){
                        throw new IOException("Read index error");
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    try {
                        socketChannel.close();
                    } catch (IOException ignored) {
                    }
                    return;
                }
                indexBuf.flip();
                int index =indexBuf.getInt();
                int objLen = indexBuf.getInt();
                ByteBuffer buffer = ByteBuffer.allocate(objLen);
                while (buffer.hasRemaining()){
                    try {
                        socketChannel.read(buffer);
                    } catch (IOException e) {
                        e.printStackTrace();
                        try {
                            socketChannel.close();
                        } catch (IOException ignored) {
                        }
                        return;
                    }
                }
                try {
                    String json = new String(buffer.array(),"utf-8");
                    Gson gson = new Gson();
                    Object param = gson.fromJson(json, Object.class);
                    Call call = new Call(index,param);
                } catch (UnsupportedEncodingException e) {
                    throw new Error("Encoding of client does not match with the server");
                }



            }
        }
    }

    private class Handler extends Thread{}

    private class Responer extends Thread{}

}
