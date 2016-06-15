/**
 *
 * Copyright © 2016 Mycelium.
 * Use of this source code is governed by an ISC
 * license that can be found in the LICENSE file.
 *
 */

package com.shuffle.player;

import com.shuffle.bitcoin.Address;
import com.shuffle.bitcoin.Coin;
import com.shuffle.bitcoin.Crypto;
import com.shuffle.bitcoin.SigningKey;
import com.shuffle.bitcoin.Transaction;
import com.shuffle.bitcoin.VerificationKey;
import com.shuffle.chan.Chan;
import com.shuffle.p2p.Bytestring;
import com.shuffle.p2p.Channel;
import com.shuffle.protocol.CoinShuffle;
import com.shuffle.protocol.Mailbox;
import com.shuffle.protocol.blame.Evidence;
import com.shuffle.protocol.message.MessageFactory;
import com.shuffle.protocol.message.Phase;
import com.shuffle.protocol.blame.Matrix;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 *
 *
 * Created by Daniel Krawisz on 2/1/16.
 */
class Player<Identity> implements Runnable {
    private static final Logger log = LogManager.getLogger(Player.class);

    private final com.shuffle.chan.packet.SessionIdentifier session;

    private final SigningKey sk;

    private final Coin coin;

    private final Crypto crypto;

    private final long time; // The time at which the join is scheduled to happen.

    private final long amount;
    private final Address addrNew;
    private final Address change;
    private final int timeout;

    Player(
            SigningKey sk,
            SessionIdentifier session,
            MessageFactory messages, // Object that knows how to create and copy messages.
            Coin coin, // Connects us to the Bitcoin or other cryptocurrency netork.
            Crypto crypto,
            long time,
            long amount,
            Address addrNew,
            Address change,
            int timeout
    ) {
        if (sk == null || coin == null || session == null || crypto == null) {
            throw new NullPointerException();
        }
        this.session = session;
        this.sk = sk;
        this.coin = coin;
        this.crypto = crypto;
        this.time = time;
        this.amount = amount;
        this.addrNew = addrNew;
        this.change = change;
        this.timeout = timeout;
    }

    @Override
    public void run() {

    }

    public Transaction coinShuffle(
            Set<Identity> identities,
            Channel<Identity, Bytestring> channel,
            Map<Identity, VerificationKey> keys, // Can be null.
            MessageFactory messages,
            Chan<Phase> chan
    ) throws ResetMessage {

        // Start by making connections to all the identies.
        for (Identity identity : identities) {
            channel.getPeer(identity);
        }

        SortedSet<VerificationKey> players = new TreeSet<>();
        players.add(sk.VerificationKey());
        // if the keys have not been given, send out those.
        if (keys == null) {
            // TODO
            keys = new HashMap<>();
        }

        for (VerificationKey key : keys.values()) {
            players.add(key);
        }

        // The eliminated players.
        SortedSet<VerificationKey> eliminated = new TreeSet<>();

        CoinShuffle shuffle = new CoinShuffle(messages, crypto, coin);

        // *** Step 1: do a round of CoinShuffle ***

        // Get the initial ordering of the players.
        int i = 1;
        SortedSet<VerificationKey> validPlayers = new TreeSet<>();
        for (VerificationKey player : players) {
            if (!eliminated.contains(player)) {
                validPlayers.add(player);
                i++;
            }
        }

        // Make an inbox for the next round.
        Mailbox mailbox = new Mailbox(sk.VerificationKey(), validPlayers, messages);

        // Send an introductory message and make sure all players agree on who is in
        // this round of the protocol.
        // TODO

        Matrix blame = null;
        try {
            // If the protocol returns correctly without throwing a Matrix, then
            // it has been successful.
            return shuffle.runProtocol(
                    amount, sk, validPlayers, addrNew, change, chan);
        } catch (Matrix m) {
            blame = m;
        } catch (Exception e) {
            // TODO must handle timeouts here.
            e.printStackTrace();
            return null;
        }

        // *** Step 2: construct error report ***

        // We must construct a message that informs other players of who will be eliminated.
        // This message must say who will be eliminated and who will remain, and it must
        // provide evidence as to why the eliminated players should be eliminated, if it would
        // not be obvious to everyone.

        // The set of players who are not eliminated.
        SortedSet<VerificationKey> remaining = new TreeSet<>();
        // The set of players who have been blamed, the players blaming them,
        // and the evidence provided.
        Map<VerificationKey, Map<VerificationKey, Evidence>> blamed = new HashMap<>();

        // Next we go through players and find those which are not eliminated.
        for (VerificationKey player : players) {
            // Who blames this player?
            Map<VerificationKey, Evidence> accusers = blame.getAccusations(player);

            // If nobody has blamed this player, then he's ok and can be stored in remaining.
            if (accusers == null || accusers.isEmpty()) {
                remaining.add(player);
            } else {
                blamed.put(player, accusers);
            }
        }

        // Stores evidences required to eliminate players.
        Map<VerificationKey, Evidence> evidences = new HashMap<>();

        // Next go through the set of blamed players and decide what to do with them.
        for (Map.Entry<VerificationKey, Map<VerificationKey, Evidence>> entry : blamed.entrySet()) {
            VerificationKey player = entry.getKey();

            Map<VerificationKey, Evidence> accusers = entry.getValue();

            // Does everyone blame this player except himself?
            Set<VerificationKey> everyone = new HashSet<>();
            everyone.addAll(players);
            everyone.removeAll(accusers.keySet());

            if (everyone.size() == 0 || everyone.size() == 1 && everyone.contains(player)) {
                eliminated.add(player); // Can eliminate this player without extra evidence.
                continue;
            }

            // Why is this player blamed? Is it objective?
            // If not, include evidence.
            // sufficient contains sufficient evidence to eliminate the player.
            // (theoretically not all other players could have provided logically equivalent
            // evidence against him, so we just need sufficient evidence.)
            Evidence sufficient = null;
            f : for (Evidence evidence : accusers.values()) {
                switch (evidence.reason) {
                    // TODO all cases other than default are not complete.
                    case DoubleSpend:
                        // fallthrough
                    case InsufficientFunds:
                        // fallthrough
                    case InvalidSignature: {
                        sufficient = evidence;
                        break;
                    }
                    case NoFundsAtAll: {
                        if (sufficient == null) {
                            sufficient = evidence;
                        }
                        break;
                    }
                    default: {
                        // Other cases than those specified above are are objective.
                        // We can be sure that other players agree
                        // that this player should be eliminated.
                        sufficient = null;
                        eliminated.add(player);
                        break f;
                    }
                }
            }

            // Include evidence if required.
            if (sufficient != null) {
                evidences.put(player, sufficient);
            }
        }

        // Remove eliminated players from blamed.
        for (VerificationKey player : eliminated) {
            blamed.remove(player);
        }

        if (blamed.size() > 0) {
            // TODO How could this happen and what to do about it?
        }

        throw new ResetMessage();
    }
}
