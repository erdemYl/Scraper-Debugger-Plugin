package scraper.debugger.tree;

import java.util.*;

/**
 * Simple implementation of a prefix tree without any node compression or space efficiency.
 * Based on dynamically growing radix mechanism in each node, with the help of java hash maps.
 */
public class Trie<V> implements PrefixTree<V> {
    private final TrieNode<V> headNode;
    private final Set<String> keys;
    private static final int INITIAL_RADIX_LENGTH = 4;
    private int size;

    /* Whether keys will be stored explicitly in a set or not. */
    private final boolean storeKeys;

    private final Object mutex = new Object();


    public Trie() {
        headNode = new TrieNode<>(new HashMap<>(INITIAL_RADIX_LENGTH));
        keys = null;
        storeKeys = false;
    }

    public Trie(boolean storeKeys) {
        headNode = new TrieNode<>(new HashMap<>(INITIAL_RADIX_LENGTH));
        keys = storeKeys ? new HashSet<>() : null;
        size = 0;
        this.storeKeys = storeKeys;
    }

    @Override
    public V get(String key) {
        synchronized (mutex) {
            // first check the size
            if (size() == 0 || key == null) {
                return null;
            }

            // convert string to chars
            char[] chars = key.toCharArray();

            // start from beginning state
            Node<V> node = headNode;

            // give chars to automaton
            int i = 0;
            try {
                // shifting chars one by one, changing states
                while (i < chars.length) {
                    node = node.shift(chars[i]);
                    i++;
                }
                //return value of the end state, if automaton stops in an end state
                return node.isEndNode()
                        ? ((EndNode<V>) node).getValue()
                        : null;

            } catch (NoStateFound e) {
                return null;
            }
        }
    }

    @Override
    public void put(String key, V value) {
        synchronized (mutex) {
            if (key.isBlank()) {
                // do nothing
                return;
            }

            if (storeKeys) {
                assert keys != null;
                keys.add(key);
            }
            size++;

            char[] chars = key.toCharArray();
            int length = chars.length;

            // begin with start state
            Node<V> n = headNode;
            Node<V> prevNode;
            int i = 0;
            try {
                // shifting can throw NoStateFound exception
                while (i < length - 1) {
                    n = n.shift(chars[i]);
                    i++;
                }
                prevNode = n;
                n = n.shift(chars[i]);

                if (n.isEndNode()) {
                    // change value of the node
                    ((EndNode<V>) n).setValue(value);
                } else {
                    // change TrieNode to EndNode and store value
                    n = ((TrieNode<V>) n).toEndNode(value);
                    // then add new transition to previous node
                    prevNode.transitionAdd(chars[i], n);
                }
            } catch (NoStateFound e) {
                while (i < length - 1) {
                    Node<V> node = new TrieNode<>(new HashMap<>(INITIAL_RADIX_LENGTH));
                    n.transitionAdd(chars[i], node);
                    n = node;
                    i++;
                }
                EndNode<V> end = new EndNode<>(value, new HashMap<>(INITIAL_RADIX_LENGTH));
                n.transitionAdd(chars[i], end);
            }
        }
    }

    @Override
    public void remove(String key) {
        synchronized (mutex) {
            char[] input = key.toCharArray();
            int i = 0;
            // set start state
            Node<V> node = headNode;
            Node<V> prevNode;

            // put input to automaton
            try {
                // begin shifting the input chars
                while (i < input.length - 1) {
                    // throws exception, when shifting is not possible
                    node = node.shift(input[i]);
                    i++;
                }
                prevNode = node;
                node = node.shift(input[i]);

                // check, if the automaton has reached an end state
                if (node.isEndNode()) {
                    if (storeKeys) {
                        assert keys != null;
                        keys.remove(key);
                    }
                    size--;

                    if (node.getTransitions().isEmpty()) {
                        // remove node completely
                        prevNode.transitionDelete(input[i]);
                    } else {
                        // convert end node to normal trie node and
                        // change the transition in previous state
                        prevNode.transitionAdd(
                                input[i],
                                ((EndNode<V>) node).toTrieNode()
                        );
                    }
                }
            } catch (NoStateFound e) {
                // do nothing, since the automaton does not accept given string.
            }
        }
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public void keys(String[] array) {
        synchronized (mutex) {
            if (array == null || array.length < size) {
                throw new IllegalArgumentException();
            } else {
                if (!storeKeys) {
                    Set<String> keys = gatherKeysFor("");
                    int i = 0;
                    for (String k : keys) {
                        array[i] = k;
                        i++;
                    }
                    return;
                }

                int i = 0;
                int length = array.length;
                assert keys != null;
                Iterator<String> iter = keys.iterator();

                while (i < length && iter.hasNext()) {
                    array[i] = iter.next();
                    i++;
                }
            }
        }
    }

    /**
     * Fills given set with keys, which have
     * as prefix the given prefix.
     *
     */
    @Override
    public void prefixKeys(String prefix, Set<String> set) {
        synchronized (mutex) {
            if (set != null) {
                if (!storeKeys) {
                    set.addAll(gatherKeysFor(prefix));
                    return;
                }

                // create a new automaton
                Trie<Boolean> automaton = new Trie<>();

                // automaton only accepts strings with given prefix
                automaton.put(prefix, true);

                // now put all keys one by one to automaton
                assert keys != null;
                for (String examinedKey : keys) {
                    char[] keyChars = examinedKey.toCharArray();

                    // starting with start state
                    Node<V> node = (Node<V>) automaton.headNode;
                    try {
                        // shifting chars one by one
                        for (char c : keyChars) {
                            node = node.shift(c);

                            // check if the end state is reached
                            if (node.isEndNode()) {
                                set.add(examinedKey);
                                break;
                            }
                        }
                    } catch (NoStateFound e) {
                        // do nothing, since the examined key
                        // hasn't got the wanted prefix.
                    }
                }
            }
        }
    }

    @Override
    public void prefixValues(String prefix, Set<V> set) {
        synchronized (mutex) {
            if (set != null) {
                if (!storeKeys) {
                    set.addAll(gatherValuesFor(prefix));
                    return;
                }

                // create a new automaton
                Trie<Boolean> automaton = new Trie<>();

                // automaton only accepts strings with given prefix
                automaton.put(prefix, true);

                // now put all keys one by one to automaton

                assert keys != null;
                for (String key : keys) {
                    char[] keyChars = key.toCharArray();

                    // starting with start state
                    Node<V> node = (Node<V>) automaton.headNode;
                    try {
                        // shifting chars one by one
                        for (char c : keyChars) {
                            node = node.shift(c);

                            // check if the end state is reached
                            if (node.isEndNode()) {
                                set.add(((EndNode<V>) node).value);
                                break;
                            }
                        }
                    } catch (NoStateFound e) {
                        // do nothing, since the examined key
                        // hasn't got the wanted prefix.
                    }
                }
            }
        }
    }

    @Override
    public List<V> getValuesOn(String key) {
        synchronized (mutex) {
            if (key == null || key.isEmpty()) return List.of();
            List<V> values = new LinkedList<>();

            char[] input = key.toCharArray();
            Node<V> n = headNode;
            for (char c : input) {
                try {
                    n = n.shift(c);
                    if (n.isEndNode()) {
                        values.add(((EndNode<V>) n).getValue());
                    }
                } catch (NoStateFound e) {
                    // given key is not available
                    return List.of();
                }
            }
            return Collections.unmodifiableList(values);
        }
    }

    @Override
    public Map.Entry<String, V> getLongestMatchedEntry(String key) {
        synchronized (mutex) {
            if (key == null) return null;

            char[] input = key.toCharArray();
            Node<V> n = headNode;
            EndNode<V> longest = null;
            int last = 0;
            for (int i = 0; i < input.length; i++) {
                try {
                    n = n.shift(input[i]);
                    if (n.isEndNode()) {
                        longest = (EndNode<V>) n;
                        last = i;
                    }
                } catch (NoStateFound e) {
                    break;
                }
            }

            if (longest == null) return null;
            else {
                return new AbstractMap.SimpleImmutableEntry<>(
                        Arrays.toString(Arrays.copyOf(input, last + 1)),
                        longest.getValue());
            }
        }
    }


    @SuppressWarnings("Duplicates")
    public List<String> getDirectChildKeysOf(String key) {
        synchronized (mutex) {
            if (key == null) return List.of();
            List<String> children = new LinkedList<>();

            char[] input = key.toCharArray();
            Node<V> n = headNode;
            for (char c : input) {
                try {
                    n = n.shift(c);
                } catch (NoStateFound e) {
                    return List.of();
                }
            }
            if (n.isEndNode()) {
                n.getTransitions().forEach((ch, node) -> {
                    if (node.isEndNode()) children.add(String.valueOf(ch));
                    else {
                        findDirectEndNodesPOSTFIX(node).forEach(str -> {
                            children.add(ch + str);
                        });
                    }
                });
                return Collections.unmodifiableList(children);
            }
            return List.of();
        }
    }

    @SuppressWarnings("Duplicates")
    public List<V> getDirectChildValuesOf(String key) {
        synchronized (mutex) {
            if (key == null) return List.of();
            List<V> children = new ArrayList<>();

            char[] input = key.toCharArray();
            Node<V> n = headNode;
            for (char c : input) {
                try {
                    n = n.shift(c);
                } catch (NoStateFound e) {
                    return List.of();
                }
            }
            if (n.isEndNode()) {
                n.getTransitions().forEach((ch, node) -> {
                    if (node.isEndNode()) children.add(((EndNode<V>) node).value);
                    else {
                        children.addAll(findDirectEndNodesVALUE(node));
                    }
                });
                return Collections.unmodifiableList(children);
            }
            return List.of();
        }
    }


    public boolean isEmpty() {
        synchronized (mutex) {
            return size == 0;
        }
    }


    @Override
    public boolean contains(String key) {
        return get(key) != null;
    }


    private List<String> findDirectEndNodesPOSTFIX(Node<V> n) {
        List<String> ends = new LinkedList<>();
        n.getTransitions().forEach((ch, node) -> {
            if (node.isEndNode()) ends.add(String.valueOf(ch));
            else {
                findDirectEndNodesPOSTFIX(node).forEach(str -> {
                    ends.add(ch + str);
                });
            }
        });
        return Collections.unmodifiableList(ends);
    }

    private List<V> findDirectEndNodesVALUE(Node<V> n) {
        List<V> ends = new ArrayList<>();
        n.getTransitions().forEach((ch, node) -> {
            if (node.isEndNode()) ends.add(((EndNode<V>) node).value);
            else ends.addAll(findDirectEndNodesVALUE(node));
        });
        return Collections.unmodifiableList(ends);
    }

    @SuppressWarnings("Duplicates")
    private Set<String> gatherKeysFor(String prefix) {
        Node<V> start = headNode;

        if (!prefix.isEmpty()) {
            try {
                char[] chars = prefix.toCharArray();
                for (char c : chars) {
                    start = start.shift(c);
                }
            } catch (NoStateFound e) {
                return new HashSet<>();
            }
        }

        return gatherKeysHelper(prefix, start);
    }

    @SuppressWarnings("Duplicates")
    private Set<V> gatherValuesFor(String prefix) {
        Node<V> start = headNode;

        if (!prefix.isEmpty()) {
            try {
                char[] chars = prefix.toCharArray();
                for (char c : chars) {
                    start = start.shift(c);
                }
            } catch (NoStateFound e) {
                return new HashSet<>();
            }
        }

        return gatherValuesHelper(prefix, start);
    }

    private Set<String> gatherKeysHelper(String prefix, Node<V> start) {
        Set<String> set = new HashSet<>();
        if (start.isEndNode()) {
            set.add(prefix);
        }
        start.getTransitions().forEach((ch, n) -> set.addAll(gatherKeysHelper(prefix + ch, n)));
        return set;
    }

    private Set<V> gatherValuesHelper(String prefix, Node<V> start) {
        Set<V> set = new HashSet<>();
        if (start.isEndNode()) {
            set.add(((EndNode<V>) start).value);
        }
        start.getTransitions().forEach((ch, n) -> set.addAll(gatherValuesHelper(prefix + ch, n)));
        return set;
    }




    // ---------------------------
    //          Utils
    // ---------------------------


    /**
     * Interface for TrieMap nodes.
     * <p>
     * There are two kinds of nodes: EndNode and TrieNode.
     * The fist one stores the value, while the other
     * is not.
     *
     * @param <V> is a generic type.
     */
    private static abstract class Node<V> {
        abstract boolean isEndNode();

        abstract void transitionAdd(Character ch, Node<V> next);

        abstract void transitionDelete(Character ch);

        abstract Map<Character, Node<V>> getTransitions();

        abstract Node<V> shift(Character ch) throws NoStateFound;
    }


    /**
     * Represents the end nodes in TrieMap.
     */
    private static final class EndNode<V> extends Node<V> {
        private V value;

        /**
         * A map of character transitions from this node.
         */
        private final Map<Character, Node<V>> transitions;

        public EndNode(V value, Map<Character, Node<V>> transitions) {
            this.value = value;
            this.transitions = transitions;
        }

        @Override
        public boolean isEndNode() {
            return true;
        }

        @Override
        public void transitionAdd(Character ch, Node<V> next) {
            transitions.put(ch, next);
        }

        @Override
        public void transitionDelete(Character ch) {
            transitions.remove(ch);
        }

        @Override
        public Map<Character, Node<V>> getTransitions() {
            return transitions;
        }

        @Override
        public Node<V> shift(Character ch) throws NoStateFound {
            Node<V> nextState = transitions.get(ch);
            if (nextState != null) {
                return nextState;
            } else {
                throw new NoStateFound();
            }
        }

        /**
         * Converts this EndNode object to TrieNode.
         *
         * @return a new TrieNode.
         */
        TrieNode<V> toTrieNode() {
            return new TrieNode<>(transitions);
        }

        V getValue() {
            return value;
        }

        void setValue(V value) {
            this.value = value;
        }
    }


    /**
     * Represents nodes except end nodes in TrieMap.
     */
    private static final class TrieNode<V> extends Node<V> {

        /**
         * Map of character transitions from this node to other nodes.
         */
        private final Map<Character, Node<V>> transitions;

        /**
         * Constructing a node with its transitions.
         *
         * @param transitions is given.
         */
        TrieNode(Map<Character, Node<V>> transitions) {
            this.transitions = transitions;
        }

        @Override
        public boolean isEndNode() {
            return false;
        }

        @Override
        public void transitionAdd(Character ch, Node<V> next) {
            transitions.put(ch, next);
        }

        @Override
        public void transitionDelete(Character ch) {
            transitions.remove(ch);
        }

        @Override
        public Map<Character, Node<V>> getTransitions() {
            return transitions;
        }

        @Override
        public Node<V> shift(Character ch) throws NoStateFound {
            Node<V> nextState = transitions.get(ch);
            if (nextState != null) {
                return nextState;
            } else {
                throw new NoStateFound();
            }
        }

        /**
         * Converts this TrieNode object to EndNode.
         *
         * @param value is the given value to store with key.
         * @return a new EndNode.
         */
        EndNode<V> toEndNode(V value) {
            return new EndNode<>(value, this.transitions);
        }
    }
}

class NoStateFound extends Exception {
}