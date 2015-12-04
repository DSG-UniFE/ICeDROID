package unife.icedroid.core.routingalgorithms;

import android.content.Intent;
import unife.icedroid.core.BaseMessage;
import unife.icedroid.core.NeighborInfo;
import unife.icedroid.core.ICeDROIDMessage;
import unife.icedroid.core.managers.MessageQueueManager;
import unife.icedroid.core.managers.NeighborhoodManager;
import unife.icedroid.services.ApplevDisseminationChannelService;
import unife.icedroid.utils.Settings;
import java.util.ArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;

public class SprayAndWaitThread extends Thread {
    private static final String TAG = "SprayAndWaitThread";
    private static final boolean DEBUG = true;

    private ArrayList<ICeDROIDMessage> messages;
    private ArrayList<ArrayList<NeighborInfo>> ackLists;
    private ArrayList<Integer> Ls;
    private Lock lock;
    private Condition mQueue;

    public SprayAndWaitThread() {
        lock = new ReentrantLock();
        mQueue = lock.newCondition();
        messages = new ArrayList<>(0);
        ackLists = new ArrayList<>(0);
        Ls = new ArrayList<>(0);
    }

    @Override
    public void run() {
        lock.lock();
        //Wait for the first message
        while (messages.size() == 0) {
            try {
                mQueue.await();
            } catch (Exception ex) {
            }
        }
        lock.unlock();

        ICeDROIDMessage msg;
        NeighborhoodManager neighborhoodManager = NeighborhoodManager.getNeighborhoodManager();
        MessageQueueManager messageQueueManager = MessageQueueManager.getMessageQueueManager();
        int L = 1;
        int msgL;
        Intent intent = new Intent();
        ArrayList<NeighborInfo> ackL;
        int index = 0;
        long lastUpdate = 0;
        while (!Thread.interrupted()) {
            lock.lock();
            if (messages.size() == 0) {
                interrupt();
                lock.unlock();
            } else {
                lock.unlock();
                msg = messages.get(index);

                if (!isExpired(msg)) {

                    if (Ls.get(index) == null) {
                        Ls.remove(index);
                        Ls.add(index, L);
                        msgL = L;
                        if (neighborhoodManager.getNumberOfNeighbors() == 0) {
                            intent.putExtra(ApplevDisseminationChannelService.EXTRA_ADC_MESSAGE,
                                                                                            msg);
                            Settings.getSettings().getADCThread().add(intent);
                        }
                    } else {
                        msgL = Ls.get(index);
                    }

                    ackL = ackLists.get(index);
                    for (NeighborInfo neighbor : neighborhoodManager.
                                                         whoHasThisMessageButNotInterested(msg)) {
                        if (!ackL.contains(neighbor)) {
                            msgL = (int) Math.ceil(L / 2);
                            ackL.add(neighbor);
                            if (msgL <= 0) {
                                break;
                            }
                        }
                    }
                    Ls.remove(index);
                    Ls.add(index, msgL);

                    if (msgL > 0) {
                        if (neighborhoodManager.isThereNeighborSubscribedToChannel(msg)) {
                            msg.setProperty("L", 0);
                            intent.putExtra(ApplevDisseminationChannelService.EXTRA_ADC_MESSAGE,
                                                                                            msg);
                            Settings.getSettings().getADCThread().add(intent);
                        } else {
                            if (neighborhoodManager.
                                    isThereNeighborNotInterestedToMessageAndNotCached(msg)) {
                                msg.setProperty("L", msgL);
                                intent.putExtra(ApplevDisseminationChannelService.EXTRA_ADC_MESSAGE,
                                        msg);
                                Settings.getSettings().getADCThread().add(intent);
                            }
                        }

                    } else {
                        msg.setProperty("L", msgL);
                        remove(msg, index);
                        messageQueueManager.removeMessageFromForwardingMessages(msg);
                        messageQueueManager.removeMessageFromCachedMessages(msg);
                        messageQueueManager.addToCache(msg);
                    }
                } else {
                    remove(msg, index);
                }

                boolean waitForUpdate = false;
                lock.lock();
                if (index + 1 >= messages.size()) {
                    index = 0;
                    waitForUpdate = true;
                } else {
                    index++;
                }
                lock.unlock();

                if (waitForUpdate) {
                    lastUpdate = neighborhoodManager.isThereAnUpdate(lastUpdate);
                }
            }
        }
    }

    public boolean add(ICeDROIDMessage msg) {
        boolean result = false;
        lock.lock();
        if (!isInterrupted()) {
            messages.add(msg);
            ackLists.add(new ArrayList<NeighborInfo>());
            Ls.add(null);
            mQueue.signal();
            result = true;
        }
        lock.unlock();
        return result;
    }

    public void remove(ICeDROIDMessage msg, int index) {
        lock.lock();
        messages.remove(index);
        ackLists.remove(index);
        Ls.remove(index);
        lock.unlock();
    }

    private boolean isExpired(BaseMessage msg) {
        if (msg.getTtl() != BaseMessage.INFINITE_TTL) {
            if (msg.getCreationTime().getTime() + msg.getTtl() < System.currentTimeMillis()) {
                return true;
            }
        }
        return false;
    }
}