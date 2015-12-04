package unife.icedroid.services;

import android.content.Intent;
import android.util.Log;
import unife.icedroid.core.BaseMessage;
import unife.icedroid.core.HelloMessage;
import unife.icedroid.core.ICeDROIDMessage;
import unife.icedroid.core.NeighborInfo;
import unife.icedroid.core.managers.*;
import unife.icedroid.utils.Settings;

import java.util.ArrayList;
import java.util.Random;

public class ApplevDisseminationChannelService extends Thread {
    private static final String TAG = "AppDissChannelService";
    private static final boolean DEBUG = true;

    public static final String EXTRA_ADC_MESSAGE = "unife.icedroid.ADC_MESSAGE";
    public static final double CACHING_PROBABILITY = 0.1;
    public static final double FORWARD_PROBABILITY = 0.3;

    private MessageQueueManager messageQueueManager;
    private ChannelListManager channelListManager;
    private NeighborhoodManager neighborhoodManager;
    private ArrayList<Intent> intents;
    private OnMessageReceiveListener onMessageReceiveListener;


    public ApplevDisseminationChannelService() {
        messageQueueManager = MessageQueueManager.getMessageQueueManager();
        channelListManager = ChannelListManager.getChannelListManager();
        neighborhoodManager = NeighborhoodManager.getNeighborhoodManager();
        intents = new ArrayList<>(0);
        onMessageReceiveListener = unife.icedroid.Settings.getSettings().getListener();
    }

    @Override
    public void run() {
        Intent intent;
        while (!Thread.interrupted()) {
            synchronized (intents) {
                while (intents.size() == 0) {
                    try {
                        intents.wait();
                    } catch (Exception ex) {}
                }
                intent = intents.get(0);
                intents.remove(0);
            }

            ICeDROIDMessage iceMessage =
                    (ICeDROIDMessage) intent.getSerializableExtra(EXTRA_ADC_MESSAGE);

            //There's a new regular message, first it must be decided whether to cache or not
            //and following whether to forward it or not
            if (iceMessage != null) {
                //This host's messages
                if (iceMessage.getHostID().equals(Settings.getSettings().getHostID())) {
                    switch (Settings.getSettings().getRoutingAlgorithm()) {
                        case SPRAY_AND_WAIT:
                            messageQueueManager.removeMessageFromForwardingMessages(iceMessage);
                            messageQueueManager.removeMessageFromCachedMessages(iceMessage);
                            forwardMessage(iceMessage, true);
                            messageQueueManager.addToCache(iceMessage);
                            break;
                        default:
                            break;
                    }

                } else {
                    //Other hosts' messages
                    boolean toCache = true;

                    if (!messageQueueManager.isCached(iceMessage) &&
                        !messageQueueManager.isDiscarded(iceMessage) &&
                        !messageQueueManager.isExpired(iceMessage)) {

                        if (channelListManager.isSubscribedToChannel(iceMessage)) {
                            onMessageReceiveListener.receive(iceMessage);
                        } else {
                            switch (Settings.getSettings().getRoutingAlgorithm()) {
                                case SPRAY_AND_WAIT:
                                    Integer L = iceMessage.getProperty("L");
                                    if (L == null || L <= 0) {
                                        Random random = new Random(System.currentTimeMillis());
                                        if (random.nextDouble() > CACHING_PROBABILITY) {
                                            toCache = false;
                                            messageQueueManager.addToDiscarded(iceMessage);
                                        }
                                    }
                                    break;
                                default:
                                    break;
                            }
                        }

                        if (toCache) {
                            messageQueueManager.addToCache(iceMessage);
                            forwardMessage(iceMessage, false);
                        }
                    }
                }

            }
            //There's a new neighbor or a neighbor update
            else {
                HelloMessage helloMessage = (HelloMessage) intent.getSerializableExtra(HelloMessage.
                        EXTRA_HELLO_MESSAGE);

                if (intent.hasExtra(NeighborInfo.EXTRA_NEW_NEIGHBOR)) {
                    messageQueueManager.removeICeDROIDMessagesFromForwardingMessages();

                    for (ICeDROIDMessage msg : messageQueueManager.getCachedMessages()) {
                        forwardMessage(msg, false);
                    }

                } else {
                    //If everyone has a message then stop forwarding it
                    if (DEBUG)
                        Log.i(TAG, "Handling an HelloMessage UPDATE " + helloMessage.getMsgID());

                    ArrayList<BaseMessage> fm = messageQueueManager.getForwardingMessages();

                    for (BaseMessage m : fm) {
                        if (m.getTypeOfMessage().equals(ICeDROIDMessage.ICEDROID_MESSAGE)) {
                            if (neighborhoodManager.everyoneHasThisMessage(m)) {
                                if (DEBUG) Log.i(TAG, "Handling an HelloMessage: removed " + m);
                                messageQueueManager.removeMessageFromForwardingMessages(m);
                            }
                        }
                    }
                }
            }
        }
    }

    public void add(Intent intent) {
        synchronized (intents) {
            intents.add(intent);
            intents.notifyAll();
        }
    }

    private void forwardMessage(ICeDROIDMessage msg, boolean thisHostMessage) {
        boolean send = false;
        switch (Settings.getSettings().getRoutingAlgorithm()) {
            case SPRAY_AND_WAIT:
                //If the message is new from this host
                if (thisHostMessage) {
                    if (neighborhoodManager.isThereNeighborSubscribedToChannel(msg)) {
                        send = true;
                    } else if (neighborhoodManager.isThereNeighborWithoutThisMessage(msg)) {
                        Random random = new Random(System.currentTimeMillis());
                        if (random.nextDouble() <= FORWARD_PROBABILITY) {
                            send = true;
                        }
                    }
                } else {
                    //If we are not in the spraying phase or the message is not new,
                    //then according to the Spray and Wait Algorithm the message must be
                    //delivered only if there is a direct interested neighbor.
                    if (neighborhoodManager.isThereNeighborSubscribedToChannel(msg)) {
                        send = true;
                    }
                }
                break;
            default:
                break;
        }

        if (send) {
            messageQueueManager.addToForwardingMessages(msg);
        }

    }

    public interface OnMessageReceiveListener {
        public void receive(ICeDROIDMessage message);
    }
}