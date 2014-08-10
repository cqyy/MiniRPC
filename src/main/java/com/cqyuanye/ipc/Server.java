package com.cqyuanye.ipc;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;


/**
 * Created by Kali on 14-8-4.
 */
abstract class Server {

    private final BlockingDeque<Call> callQueue = new LinkedBlockingDeque<>();
    private final List<Connection> connections = new LinkedList<>();

    private final Listener listener;
    private final Handler[] handlers;
    private final Responer responer;

    public Server(InetSocketAddress address) throws IOException {
        listener = new Listener(address);
        responer = new Responer();
        handlers = new Handler[1];
        for (int i = 0; i < handlers.length; i++) {
            handlers[i] = new Handler(responer);
        }
    }

    public void start() {
        listener.start();
        for (Handler handler : handlers) {
            handler.start();
        }
        responer.start();
    }

    private class Connection {
        private final SocketChannel channel;
        final LinkedList<Call> calls = new LinkedList<>();

        public Connection(SocketChannel channel) {
            this.channel = channel;
        }

        public void readOneRPC() {
            boolean error = false;
            try {
                ByteBuffer indexBuf = ByteBuffer.allocate(8);       //int * 2
                while (indexBuf.hasRemaining()) {
                    channel.read(indexBuf);
                }
                indexBuf.flip();
                int index = indexBuf.getInt();
                int len = indexBuf.getInt();
                ByteBuffer buffer = ByteBuffer.allocate(len);
                while (buffer.hasRemaining()) {
                    channel.read(buffer);
                }
                ByteArrayInputStream inputStream = new ByteArrayInputStream(buffer.array());
                ObjectInputStream objectInputStream = new ObjectInputStream(inputStream);
                Serializable param = (Serializable) objectInputStream.readObject();
                Call call = new Call(index, param, this);
                callQueue.put(call);
            } catch (ClassNotFoundException e1) {
                e1.printStackTrace();
                error = true;
            } catch (IOException e1) {
                e1.printStackTrace();
                error = true;
            } catch (InterruptedException ignored) {
            }
            if (error) {
                try {
                    channel.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private class Call {
        final int index;
        final Serializable param;
        final Connection connection;
        private ByteBuffer response;

        public Call(int index, Serializable param, Connection connection) {
            this.index = index;
            this.param = param;
            this.connection = connection;
        }
    }

    private class Listener extends Thread {

        private volatile boolean shouldRun = true;

        private final InetSocketAddress server;
        private final ServerSocketChannel serverSocketChannel;
        private final Selector selector;
        private final Reader[] readers;

        private int nextReaderIndex = 0;

        public Listener(InetSocketAddress address) throws IOException {
            this.selector = Selector.open();
            this.server = address;
            readers = new Reader[1];
            for (int i = 0; i < readers.length; i++) {
                try {
                    readers[i] = new Reader(Selector.open());
                    Thread thread = new Thread(readers[i]);
                    thread.setDaemon(true);
                    thread.start();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (server.isUnresolved()) {
                throw new IOException("Unknown host of server " + server);
            }
            serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.bind(server);
            serverSocketChannel.configureBlocking(false);
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
        }

        private Reader getReader() {
            Reader reader = readers[nextReaderIndex];
            nextReaderIndex = (nextReaderIndex + 1) % readers.length;
            return reader;
        }


        public void end() {
            shouldRun = false;
            selector.wakeup();
        }

        @Override
        public void run() {
            while (shouldRun) {
                try {
                    selector.select(60 * 1000);
                    Set<SelectionKey> keys = selector.selectedKeys();
                    Iterator<SelectionKey> iterator = keys.iterator();
                    while (iterator.hasNext()) {
                        SelectionKey key = iterator.next();
                        if (key.isValid() && key.isAcceptable()) {
                            SocketChannel channel = ((ServerSocketChannel) key.channel()).accept();
                            channel.configureBlocking(false);
                            Connection con = new Connection(channel);
                            connections.add(con);
                            Reader reader = getReader();
                            try {
                                reader.startAdd();
                                reader.registerChannel(channel).attach(con);
                            } finally {
                                reader.finishedAdd();
                            }
                        }
                        iterator.remove();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        private class Reader implements Runnable {
            private volatile boolean adding;
            private final Selector selector;

            public Reader(Selector selector) {
                this.selector = selector;
            }

            public void startAdd() {
                adding = true;
                selector.wakeup();
            }

            public void finishedAdd() {
                adding = false;
                synchronized (this) {
                    this.notifyAll();
                }
            }

            public SelectionKey registerChannel(SelectableChannel channel) throws ClosedChannelException {
                return channel.register(selector, SelectionKey.OP_READ);
            }

            @Override
            public void run() {
                while (true) {
                    try {
                        selector.select();
                        if (adding) {
                            synchronized (this) {
                                try {
                                    this.wait();
                                } catch (InterruptedException ignored) {
                                }
                            }
                        }
                        Set<SelectionKey> keys = selector.selectedKeys();
                        Iterator<SelectionKey> iterator = keys.iterator();
                        while (iterator.hasNext()) {
                            SelectionKey key = iterator.next();
                            if (key.isValid() && key.isReadable()) {
                                doRead(key);
                            }
                            iterator.remove();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                }
            }

            private void doRead(SelectionKey key) {
                Connection connection = (Connection) key.attachment();
                assert connection != null;
                connection.readOneRPC();
            }
        }
    }

    private class Handler extends Thread {

        private final Responer responer;

        public Handler(Responer responser) {
            this.responer = responser;
        }

        @Override
        public void run() {
            while (true) {
                Call call = null;

                while (call == null){
                    try {
                        call = callQueue.take();
                    } catch (InterruptedException ignored) {
                    }
                }

                if (call != null) {
                    Object value = null;
                    Throwable exception = null;
                    try {
                        value = call(call.param);
                    } catch (Throwable throwable) {
                        exception = throwable;
                    }
                    ByteArrayOutputStream os = new ByteArrayOutputStream();
                    DataOutputStream ds = new DataOutputStream(os);
                    try {
                        ds.writeInt(call.index);
                        if (exception != null) {
                            ds.writeBoolean(false);
                            ObjectOutputStream objectOutputStream = new ObjectOutputStream(os);
                            objectOutputStream.writeObject(exception);
                        } else {
                            ds.writeBoolean(true);
                            ObjectOutputStream objectOutputStream = new ObjectOutputStream(os);
                            objectOutputStream.writeObject(value);
                        }
                        call.response = ByteBuffer.wrap(os.toByteArray());
                        os.close();
                        responer.doResponse(call);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private class Responer extends Thread {
        private final Selector selector;
        private volatile boolean adding;

        public Responer() throws IOException {
            selector = Selector.open();
            this.setDaemon(true);
        }

        @Override
        public void run() {
            while (true) {
                try {
                    selector.select();
                    while (adding) {
                        synchronized (this) {
                            try {
                                this.wait();
                            } catch (InterruptedException ignored) {
                            }
                        }
                    }
                    Set<SelectionKey> keySet = selector.selectedKeys();
                    Iterator<SelectionKey> iterator = keySet.iterator();
                    while (iterator.hasNext()) {
                        SelectionKey key = iterator.next();
                        if (key.isValid() && key.isWritable()) {
                            Connection connection = (Connection) key.attachment();
                            processOneCall(connection);
                        }
                        iterator.remove();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }

            }
        }

        public void doResponse(Call call) throws IOException {
            synchronized (call.connection) {
                call.connection.calls.addLast(call);
                if (call.connection.calls.size() == 1) {
                    processOneCall(call.connection);
                }
            }
        }

        private void processOneCall(Connection connection) throws IOException {
            Call call = connection.calls.removeFirst();
            if (call == null) {
                return;
            }
            try {
                ByteBuffer data = call.response;
                int c = connection.channel.write(data);
                if (data.hasRemaining()) {
                    connection.calls.addFirst(call);
                    if (connection.channel.keyFor(selector) == null) {
                        try {
                            adding = true;
                            selector.wakeup();
                            SelectionKey key = connection.channel.register(selector, SelectionKey.OP_WRITE);
                            key.attach(connection);
                        } finally {
                            adding = false;
                        }
                    }
                }
            } catch (IOException e) {
                throw e;
            }

        }

    }

    abstract Object call(Object param) throws Throwable;
}
