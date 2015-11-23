package unife.icedroid.services;

import android.app.IntentService;
import android.content.Intent;
import android.util.Log;
import unife.icedroid.core.Constants;
import unife.icedroid.core.RegularMessage;
import unife.icedroid.core.managers.*;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Random;

public class ApplevDisseminationChannelService extends IntentService {
    private static final String TAG = "AppDissChannelService";
    private static final boolean DEBUG = true;

    public static final double CACHING_PROBABILITY = 0.1;
    public static final double FORWARD_PROBABILITY = 0.3;

    private MessageQueueManager messageQueueManager;
    private SubscriptionListManager subscriptionListManager;
    private NeighborhoodManager neighborhoodManager;


    public ApplevDisseminationChannelService() {
        super(TAG);
        setIntentRedelivery(true);

        messageQueueManager = MessageQueueManager.getMessageQueueManager();
        subscriptionListManager = SubscriptionListManager.getSubscriptionListManager();
        neighborhoodManager = NeighborhoodManager.getNeighborhoodManager();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        RegularMessage regularMessage = (RegularMessage) intent.getSerializableExtra(Constants.
                                                                                EXTRA_ADC_MESSAGE);

        //There's a new regular message, first it must be decided whether to cache or not
        //and following whether to forward it or not
        if (regularMessage != null) {
            boolean toCache = true;

            if (!messageQueueManager.isCached(regularMessage) &&
                !messageQueueManager.isDiscarded(regularMessage)) {

                if (subscriptionListManager.isSubscribedToMessage(regularMessage)) {
                    try {
                        FileOutputStream fos = openFileOutput(
                          regularMessage.getSubscription().toString(), MODE_PRIVATE | MODE_APPEND);
                        String receptionTime = "[" +
                                regularMessage.getReceptionTime().toString() + "]";
                        String sender = regularMessage.getHostID();
                        String msg = regularMessage.getContentData();
                        String data = receptionTime + " " + sender + ": " + msg + "\n";
                        fos.write(data.getBytes());
                        fos.close();
                        if (DEBUG) Log.i(TAG,  "Message: " + data + " saved");
                    } catch (Exception ex) {
                        String msg = ex.getMessage();
                        if (DEBUG) Log.e(TAG, (msg != null) ? msg : "Impossible to save the message");
                    }
                } else if (!subscriptionListManager.isSubscribedToChannel(regularMessage)) {
                    Random random = new Random(System.currentTimeMillis());
                    if (random.nextDouble() > CACHING_PROBABILITY) {
                        toCache = false;
                        messageQueueManager.addToDiscarded(regularMessage);
                    }
                }

                if (toCache) {
                    messageQueueManager.addToCache(regularMessage);
                    forwardMessage(regularMessage);
                }
            }

        }
        //There's a new neighbor, so it must be checked which messages should be forwarded
        // and which not
        else {
            boolean newNeighbor = intent.getBooleanExtra(Constants.EXTRA_NEW_NEIGHBOR, false);

            if(newNeighbor) {
                messageQueueManager.removeRegularMessagesFromForwardingMessages();
                ArrayList<RegularMessage> cachedMessages = messageQueueManager.getCachedMessages();

                synchronized (cachedMessages) {
                    for (RegularMessage msg : cachedMessages) {
                        forwardMessage(msg);
                    }
                }
            }
        }

    }

    private void forwardMessage(RegularMessage msg) {
        boolean send = false;

        if (neighborhoodManager.isThereNeighborInterestedToMessage(msg)) {
            send = true;
        } else if (neighborhoodManager.isThereNeighborSubscribedToChannel(
                                                    msg.getSubscription().getChannelID())) {
            send = true;
        } else {
            Random random = new Random(System.currentTimeMillis());
            if (random.nextDouble() <= FORWARD_PROBABILITY) {
                send = true;
            }
        }

        if (send) {
            messageQueueManager.addToForwardingMessages(msg);
        }

    }
}