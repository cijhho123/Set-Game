package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)

    /*
     *  Hold the Tokens placed on each slot, TRUE - placed, FALSE, not placed
     *  This AtomicBoolean will save synchronization when editing single values in the 2D array
     */    
    protected final AtomicBoolean [][] tokens ;
    protected final Boolean PLACED = new Boolean(true);
    protected final Boolean NOT_PLACED = new Boolean(false);


    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {

        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;


        tokens = new AtomicBoolean [env.config.tableSize][env.config.players];
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {

        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = 0;
        //Do I need to synchronized this item? 774
        synchronized(slotToCard){
            for (Integer card : slotToCard)
                if (card != null)
                    ++cards;
        }
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     */
    public void placeCard(int card, int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        //env.logger.info("thread " + Thread.currentThread().getName() + " place card. card: " + card + " slot: "+ slot);

        //remove the card from the slot 
        if(System.identityHashCode(cardToSlot) > System.identityHashCode(slotToCard)){
            synchronized(slotToCard){
                synchronized(cardToSlot){
                    slotToCard[slot] = card;
                    cardToSlot[card] = slot;
                    cardToSlot.notifyAll();
                }
                slotToCard.notifyAll();
            }
        } else {
            synchronized(cardToSlot){
                synchronized(slotToCard){
                    slotToCard[slot] = card;
                    cardToSlot[card] = slot;
                    slotToCard.notifyAll();
                }
                cardToSlot.notifyAll();
            }
        }

        this.env.ui.placeCard(card, slot);
    }

    /**
     * Removes a card from a grid slot on the table.
     * @param slot - the slot from which to remove the card.
     */
    public Integer removeCard(int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}


        //env.logger.fine("thread " + Thread.currentThread().getName() + " remove card from slot  " + slot);
        Integer card = slotToCard[slot];

        if(card == null)
            return null;

        //remove all the tokens 
        for(int i=0; i < tokens[slot].length; i++)  //it was synchronized, I've deleted it becuase tokens is AtomicBoolean now, need to watch our 774
            removeToken(slot, i);                   //as its just tokens, it should be fine
            

        //remove the card from the slot (can't use CAS on null value) - lock the lower ID first!
        if(System.identityHashCode(cardToSlot) > System.identityHashCode(slotToCard)){
            synchronized(slotToCard){
                synchronized(cardToSlot){
                    slotToCard[slot] = null;
                    cardToSlot[card] = null;
                    cardToSlot.notifyAll();
                }
                slotToCard.notifyAll();
            }
        } else {
            synchronized(cardToSlot){
                synchronized(slotToCard){
                    slotToCard[slot] = null;
                    cardToSlot[card] = null;
                    slotToCard.notifyAll();
                }
                cardToSlot.notifyAll();
            }
        }

        this.env.ui.removeCard(slot);

        return card;
    }

    /**
     * Places a player token on a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     */
    public void placeToken(int player, int slot) {
        //env.logger.finest("thread " + Thread.currentThread().getName() + " player  " + Integer.toString(player) + "placed token at slot " + slot);
        
        synchronized(slotToCard){
            if(slotToCard[slot] != null){
                tokens[slot][player].getAndSet(PLACED);
                this.env.ui.placeToken(player, slot);
            }
            slotToCard.notifyAll();
        }
    }

    /**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return       - true iff a token was successfully removed.
     */
    public boolean removeToken(int player, int slot) {
        //env.logger.finest("thread " + Thread.currentThread().getName() + " player  " + Integer.toString(player) + "removed token at slot " + slot);

        boolean isRemoved = tokens[slot][player].getAndSet(NOT_PLACED);
        this.env.ui.removeToken(player, slot);
        return isRemoved;
    }

    //-------- added functions
    public List<Integer> getEmptySlots(){
        List<Integer> list = new ArrayList<Integer>();

        synchronized(slotToCard){
            for (int i = 0; i < slotToCard.length; i++)
                if (slotToCard[i] == null)
                    list.add(i);

            slotToCard.notifyAll();
        }
        return list;
    }
}

