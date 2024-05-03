package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /*
     * Timing objects
     */
    private long reshuffleTime = Long.MAX_VALUE;    // The time when the dealer needs to reshuffle the deck due to turn timeout.
    private long currentTime ;
    private final Object lock;
    private final Integer DEALER_TIME_TO_SLEEP_MS = 15;
    private final Integer SIXTY_SECONDS_MS = 60000;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    
    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        lock = new Object();
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");

        currentTime = System.currentTimeMillis();
        
        while (!shouldFinish()) {
            placeCardsOnTable();
            timerLoop();
            updateTimerDisplay(false);
            removeAllCardsFromTable();
        }
        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        // Is that it?  774
        this.terminate = true;

        for(int i = this.players.length-1; i >= 0; i--)
            this.players[i].terminate();
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        //check for each player if he got a set
        for(int p = 0; p < players.length; p++){
            List<Integer> selected = new ArrayList<Integer>();
            
            synchronized(this.table.tokens){
                for(int s = 0; s < this.table.tokens.length; s++){
                    if(this.table.tokens[s][p].get())
                        selected.add(s);
                }
                this.table.tokens.notifyAll();
            }

            if(selected.size() == 3){   
                //check if the set is valid
                int [] cards = selected.stream().mapToInt(i->i).toArray();
                if(env.util.testSet(cards)){
                    env.logger.fine("Player " + p + "found a set and won a point.");
                    players[p].point();   //need to make the player go to sleep for 1 second + updating the UI... maybe from the player's clas?    774

                    
                    //removing the 3 cards which formed as set
                    for(int i = 0; i < 3; i++)
                        this.table.removeCard(cards[i]);

                    updateTimerDisplay(true); //reset the timer
                }  else {
                    env.logger.fine("Player " + p + "did not find a set, a penalty was given.");
                    players[p].penalty();    //make sure the player go to sleep + update UI    774
                }

                //removing all the player's tokens after a set decleration. https://moodle.bgu.ac.il/moodle/mod/forum/discuss.php?d=700694
                for(int i = 0; i < 3; i++)
                        this.table.removeToken(p,cards[i]);
            }
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        List<Integer> emptySlots = this.table.getEmptySlots();
        
        Collections.shuffle(this.deck); //shuffle the deck

        while (this.deck.size() > 0 && (!emptySlots.isEmpty())){
            int nextSlot = emptySlots.remove(0);
            int nextCard = this.deck.remove(0);

            this.table.placeCard(nextCard, nextSlot);
            this.env.ui.placeCard(nextCard, nextSlot);
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {   
        synchronized(lock){
            try {
                lock.wait(DEALER_TIME_TO_SLEEP_MS);
            } catch (Exception e){
                e.printStackTrace();    
            }
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if(reset){ //if the clock need to be reset, 
            this.currentTime = System.currentTimeMillis();
            this.env.ui.setCountdown(SIXTY_SECONDS_MS, false);
        } else { //if the clock doesn't need to be reset, update the timer
            long nowTime = SIXTY_SECONDS_MS - (System.currentTimeMillis() - this.currentTime);
            boolean isWarnning = (nowTime <= this.env.config.turnTimeoutWarningMillis); //bonus, see the config file for info
            this.env.ui.setCountdown(nowTime, isWarnning);
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        //remove each card from the table and return it to the deck
        for(int i=0; i<this.table.slotToCard.length; i++){
            Integer removed = table.removeCard(i);

            if(removed != null)
                deck.add(removed);
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        //find the highscore
        int highscore = -1;
        for(Player p : players){
            highscore = Math.max(highscore, p.score());
        }

        //get all the players with the highscore
        List<Integer> winnersList = new ArrayList<Integer>();
        for(int i = 0; i < this.players.length; i++){
            if(this.players[i].score() == highscore)
                winnersList.add(i);
        }

        int [] winnersArray = winnersList.stream().mapToInt(i->i).toArray();
        this.env.ui.announceWinner(winnersArray);
    }
}

/* 774 - notes TODO
 * 1. according to the video helper, the timer should start only after the table finshed drawing
 * 
 */