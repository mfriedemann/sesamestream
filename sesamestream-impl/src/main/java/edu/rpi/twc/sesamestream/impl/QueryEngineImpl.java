package edu.rpi.twc.sesamestream.impl;

import edu.rpi.twc.sesamestream.BindingSetHandler;
import edu.rpi.twc.sesamestream.QueryEngine;
import edu.rpi.twc.sesamestream.SesameStream;
import edu.rpi.twc.sesamestream.Subscription;
import edu.rpi.twc.sesamestream.core.Bindings;
import edu.rpi.twc.sesamestream.core.LList;
import edu.rpi.twc.sesamestream.core.Query;
import edu.rpi.twc.sesamestream.core.QueryIndex;
import edu.rpi.twc.sesamestream.core.Term;
import net.fortytwo.flow.NullSink;
import net.fortytwo.flow.Sink;
import net.fortytwo.flow.rdf.RDFSink;
import net.fortytwo.linkeddata.CacheEntry;
import net.fortytwo.linkeddata.LinkedDataCache;
import net.fortytwo.ripple.RippleException;
import org.openrdf.model.Namespace;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.ValueFactoryImpl;
import org.openrdf.query.BindingSet;
import org.openrdf.query.MalformedQueryException;
import org.openrdf.query.QueryEvaluationException;
import org.openrdf.query.QueryLanguage;
import org.openrdf.query.algebra.Filter;
import org.openrdf.query.algebra.TupleExpr;
import org.openrdf.query.impl.MapBindingSet;
import org.openrdf.query.parser.ParsedQuery;
import org.openrdf.query.parser.QueryParserUtil;
import org.openrdf.rio.RDFHandler;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.sail.Sail;
import org.openrdf.sail.SailException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Concrete implementation of a SesameStream continuous SPARQL query engine.
 * The engine receives SPARQL queries in advance of the data they query against,
 * and answers them in a forward-chaining fashion.
 * Optionally (depending on {@link edu.rpi.twc.sesamestream.SesameStream} settings),
 * performance data is generated in the process.
 *
 * @author Joshua Shinavier (http://fortytwo.net)
 */
public class QueryEngineImpl implements QueryEngine {
    protected static final Logger logger = Logger.getLogger(QueryEngineImpl.class.getName());

    protected final QueryIndex<Value> queryIndex;

    private final QueryIndex.SolutionHandler<Value> solutionHandler;

    protected Clock clock;
    private CleanupPolicy cleanupPolicy;

    private long timeOfLastCleanup = 0;
    protected int queriesAddedSinceLastCleanup = 0;
    private int statementsAddedSinceLastCleanup = 0;

    private LinkedDataCache linkedDataCache;

    private boolean logHasChanged = false;

    protected long timeCurrentOperationBegan;

    public enum Quantity {
        Queries, Statements, Solutions,
    }

    private final Map<Quantity, Counter> counters;

    protected final Counter
            countQueries = new Counter();

    private final Counter countStatements = new Counter();

    private final Counter countSolutions = new Counter();

    protected final FilterEvaluator filterEvaluator;

    protected final Map<String, SubscriptionImpl> subscriptions = new HashMap<String, SubscriptionImpl>();

    private final Object cleanupLock = "";
    private long cleanupNow;
    private boolean active = true;

    private ExecutorService linkedDataService;
    // note: these threads are in addition to any threads created externally
    private final int httpThreadPoolSize = Runtime.getRuntime().availableProcessors() + 1;

    /**
     * Creates a new query engine with an empty index
     */
    public QueryEngineImpl() {
        queryIndex = new QueryIndex<Value>(3);  // TODO: support quads

        ValueFactory valueFactory = new ValueFactoryImpl();
        filterEvaluator = new FilterEvaluator(valueFactory);

        counters = new LinkedHashMap<Quantity, Counter>();
        counters.put(Quantity.Queries, countQueries);
        counters.put(Quantity.Statements, countStatements);
        counters.put(Quantity.Solutions, countSolutions);
        solutionHandler = new QueryIndex.SolutionHandler<Value>() {
            @Override
            public void handle(final String id, final Bindings<Value> bindings) {
                handleCandidateSolution(id, bindings);
            }
        };

        clock = new Clock() {
            @Override
            public long getTime() {
                return System.currentTimeMillis();
            }
        };

        cleanupPolicy = new CleanupPolicy() {
            @Override
            public boolean doCleanup(int secondsElapsedSinceLast,
                                     int queriesAddedSinceLast,
                                     int statementsAddedSinceLast) {
                return secondsElapsedSinceLast >= 30;
            }
        };

        clear();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (true) {
                        synchronized (cleanupLock) {
                            try {
                                cleanupLock.wait();
                            } catch (InterruptedException e) {
                                logger.warning("interrupted while waiting on TTL cleanup lock");
                            }
                        }

                        // terminated
                        if (!active) {
                            return;
                        }

                        queryIndex.removeExpired(cleanupNow);
                    }
                } catch (Throwable t) {
                    logger.log(Level.SEVERE, "TTL cleanup thread failed", t);
                }
            }
        }).start();
    }

    public void setClock(final Clock clock) {
        this.clock = clock;
    }

    @Override
    public void setCleanupPolicy(final CleanupPolicy cleanupPolicy) {
        this.cleanupPolicy = cleanupPolicy;
    }

    /**
     * Retrieves a quantity tracked when "performance metrics" are enabled
     *
     * @param quantity the quantity to retrieve
     * @return the current value of the retrieved quantity
     */
    public long get(final Quantity quantity) {
        if (!SesameStream.getDoPerformanceMetrics()) {
            throw new IllegalStateException("performance metrics are disabled; quantities are not counted");
        }

        Counter counter = counters.get(quantity);
        if (null == counter) {
            throw new IllegalArgumentException("no counter for quantity: " + quantity);
        }

        return counter.count;
    }

    /**
     * Adds a Linked Data fetching and caching layer to this query engine.
     * Once added, the Linked Data cache will listen for new triple patterns indexed by this query engine,
     * and issue corresponding HTTP requests for additional information about URIs in those patterns.
     * Any RDF statements from retrieved documents are passed into the query engine, where they may contribute
     * to query results and/or partial solutions, and may trigger further HTTP requests.
     *
     * @param cache a collection of caching metadata about Linked Data already retrieved
     * @param sail  the RDF component of the caching metadata
     * @throws SailException if a cache-level query exception occurs
     */
    public void setLinkedDataCache(final LinkedDataCache cache,
                                   final Sail sail) throws SailException {
        this.linkedDataCache = cache;
        this.linkedDataCache.setAutoCommit(true);

        if (null != linkedDataService) {
            linkedDataService.shutdown();
        }

        linkedDataService = Executors.newFixedThreadPool(httpThreadPoolSize);
    }

    public void clear() {
        queryIndex.clear();
        countQueries.reset();
        countStatements.reset();
        countSolutions.reset();

        logHeader();
    }

    public Subscription addQuery(final int ttl,
                                 final String q,
                                 final BindingSetHandler handler) throws IncompatibleQueryException, InvalidQueryException {
        // TODO
        String baseURI = "http://example.org/baseURI";

        ParsedQuery query;
        try {
            query = QueryParserUtil.parseQuery(
                    QueryLanguage.SPARQL,
                    q,
                    baseURI);
        } catch (MalformedQueryException e) {
            throw new InvalidQueryException(e);
        }

        // invalidate the Linked Data cache when a new query is added, as the evaluation of the new query may
        // require statements from data sources which have already been processed
        if (null != linkedDataCache) {
            clearLinkedDataCache();
        }

        return addQuery(ttl, query.getTupleExpr(), handler);
    }

    /**
     * Adds a new query subscription to this query engine
     *
     * @param t the query to add
     * @param h a handler for future query answers
     * @return a subscription for computation of future query answers
     * @throws IncompatibleQueryException if the syntax of the query is not supported by this engine
     */
    public Subscription addQuery(final int ttl,
                                 final TupleExpr t,
                                 final BindingSetHandler h) throws IncompatibleQueryException {
        increment(countQueries, true);
        long now = clock.getTime();
        timeCurrentOperationBegan = now;

        SparqlQuery q = new SparqlQuery(t);

        Query<Value> query = toNative(q, ttl, now);
        queryIndex.add(query);
        SubscriptionImpl s = new SubscriptionImpl(q, query, h, this);
        register(s);

        if (null != linkedDataCache) {
            for (Query.PatternInQuery<Value> p : query.getPatterns()) {
                triggerLinkedDataCache(p.getTerms());
            }
        }

        logEntry();

        queriesAddedSinceLastCleanup++;
        checkCleanup(now);

        return s;
    }

    public void addStatements(final int ttl, final Statement... statements) {
        for (Statement s : statements) {
            addStatement(ttl, s);
            statementsAddedSinceLastCleanup++;
        }

        checkCleanup(clock.getTime());
    }

    public synchronized void shutDown() {
        active = false;

        // terminate the cleanup thread
        synchronized (cleanupLock) {
            cleanupLock.notify();
        }
    }

    protected synchronized void checkCleanup(final long now) {
        int seconds = (int) ((now - timeOfLastCleanup) / 1000);

        if (cleanupPolicy.doCleanup(seconds, queriesAddedSinceLastCleanup, statementsAddedSinceLastCleanup)) {
            timeOfLastCleanup = now;
            queriesAddedSinceLastCleanup = 0;
            statementsAddedSinceLastCleanup = 0;
            cleanupNow = now;

            synchronized (cleanupLock) {
                cleanupLock.notify();
            }
        }
    }

    public RDFHandler createRDFHandler(final int ttl) {
        return new RDFHandler() {
            public void startRDF() throws RDFHandlerException {
                // do nothing
            }

            public void endRDF() throws RDFHandlerException {
                // do nothing
            }

            public void handleNamespace(String s, String s1) throws RDFHandlerException {
                // do nothing
            }

            public void handleStatement(Statement s) throws RDFHandlerException {
                try {
                    addStatements(ttl, s);
                } catch (Throwable t) {
                    throw new RDFHandlerException(t);
                }
            }

            public void handleComment(String s) throws RDFHandlerException {
                // do nothing
            }
        };
    }

    public RDFSink createRDFSink(final int ttl) {
        return new RDFSink() {
            @Override
            public Sink<Statement> statementSink() {
                return new Sink<Statement>() {
                    public void put(final Statement s) throws RippleException {
                        try {
                            addStatements(ttl, s);
                        } catch (Throwable t) {
                            throw new RippleException(t);
                        }
                    }
                };
            }

            @Override
            public Sink<Namespace> namespaceSink() {
                return new NullSink<Namespace>();
            }

            @Override
            public Sink<String> commentSink() {
                return new NullSink<String>();
            }
        };
    }

    private void addStatement(final int ttl, final Statement s) {
        increment(countStatements, false);
        long now = clock.getTime();
        timeCurrentOperationBegan = now;

        Value[] tuple = toNative(s);
        boolean changed = queryIndex.add(tuple, solutionHandler, ttl, now);

        // cue the Linked Data cache to dereference the subject and object URIs of the statement,
        // but only if at least one pattern in the index has matched the tuple
        if (changed && null != linkedDataCache) {
            triggerLinkedDataCache(tuple);
        }

        logEntry();
    }

    protected void register(final SubscriptionImpl subscription) {
        subscriptions.put(subscription.getQuery().getId(), subscription);
    }

    // free up the resources occupied by this subscription and prevent it from matching future data
    public void unregister(final SubscriptionImpl subscription) {
        queryIndex.remove(subscription.getQuery());
        subscriptions.remove(subscription.getQuery().getId());
    }

    public void renew(final SubscriptionImpl subscription, final int ttl) {
        long now = clock.getTime();
        queryIndex.renew(subscription.getQuery(), ttl, now);
    }

    private Value[] toNative(final Statement s) {
        // note: assumes tupleSize==3
        return new Value[]{s.getSubject(), s.getPredicate(), s.getObject()};
    }

    protected Query<Value> toNative(final SparqlQuery q, final int ttl, final long now) {
        List<Term<Value>[]> patterns = new LinkedList<Term<Value>[]>();
        LList<Term<Value>[]> tPatterns = q.getTriplePatterns();
        while (!tPatterns.isNil()) {
            patterns.add(tPatterns.getValue());
            tPatterns = tPatterns.getRest();
        }

        long expirationTime = SesameStream.INFINITE_TTL == ttl ? 0 : now + 1000L * ttl;
        return new Query<Value>(patterns, expirationTime);
    }

    private void clearLinkedDataCache() {
        try {
            linkedDataCache.clear();
        } catch (RippleException e) {
            logger.log(Level.SEVERE, "failed to clear Linked Data cache when adding a query", e);
        }
    }

    private void triggerLinkedDataCache(final Value[] tuple) {
        if (tuple.length >= 3) {
            Value subject = tuple[0];
            Value object = tuple[2];

            if (subject instanceof URI && object instanceof URI) {
                boolean subjectExists, objectExists;
                try {
                    subjectExists = null != linkedDataCache.peek((URI) subject, linkedDataCache.getSailConnection());
                    objectExists = null != linkedDataCache.peek((URI) object, linkedDataCache.getSailConnection());
                } catch (RippleException e) {
                    logger.log(Level.SEVERE, "Ripple exception while dereferencing (" + subject + "," + object + ")");
                    return;
                }

                if (subjectExists && !objectExists) {
                    indexLinkedDataUri((URI) object);
                } else if (objectExists && !subjectExists) {
                    indexLinkedDataUri((URI) subject);
                }
            }
        }
    }

    private void triggerLinkedDataCache(final Term<Value>[] pattern) {
        if (pattern.length >= 1) {
            Value subject = pattern[0].getValue();
            if (null != subject && subject instanceof URI) {
                indexLinkedDataUri((URI) subject);
            }

            if (pattern.length >= 3) {
                Value object = pattern[2].getValue();
                if (null != object && object instanceof URI) {
                    indexLinkedDataUri((URI) object);
                }
            }
        }
    }

    // note: as the indexing of Linked Data URIs may trigger the fetching and rdfization of data sources,
    // and as this occurs a pooled thread distinct from calling thread, query answers may be produced in that thread.
    private void indexLinkedDataUri(final URI uri) {
        if (isHttpUri(uri)) {
            linkedDataService.execute(new Runnable() {
                public void run() {
                    try {
                        linkedDataCache.retrieve(uri, linkedDataCache.getSailConnection());
                    } catch (RippleException e) {
                        logger.log(Level.SEVERE, "Ripple exception while dereferencing URI " + uri, e);
                    }
                }
            });
        }
    }

    // pre-filter URIs so as to avoid needlessly creating executor tasks
    private boolean isHttpUri(final URI uri) {
        String s = uri.stringValue();
        return s.startsWith("http://") || s.startsWith("https://");
    }

    protected void handleCandidateSolution(final String id,
                                           final Bindings<Value> bindings) {
        SubscriptionImpl subscription = subscriptions.get(id);
        if (null == subscription) {
            throw new IllegalStateException();
        }

        SparqlQuery sparqlQuery = subscription.getSparqlQuery();

        // After queries are removed from the query index, a few more query answers (from the last added statement,
        // which completed an ASK query, for example) may arrive here and need to be excluded
        if (!subscription.isActive()) {
            return;
        }

        List<Filter> filters = sparqlQuery.getFilters();

        // this BindingSet may contain non-selected and pre-projected variables, suitable
        // for filtering, but not yet a final query result
        BindingSet bs = toBindingSet(bindings);

        // apply all filters, discarding this potential solution if any filter rejects it
        if (null != filters) {
            for (Filter f : filters) {
                try {
                    if (!filterEvaluator.applyFilter(f, bs)) {
                        return;
                    }
                } catch (QueryEvaluationException e) {
                    logger.log(Level.SEVERE, "query evaluation error while applying filter", e);
                    return;
                }
            }
        }

        MapBindingSet solution = new MapBindingSet();

        // remove non-selected variables and project the final names of the selected variables
        for (String key : sparqlQuery.getBindingNames()) {
            Value value = bindings.get(key);
            if (null == value) {
                //if (null == query.getConstants() || !query.getConstants().keySet().contains(key)) {
                //    throw new IllegalStateException("no value for variable " + key);
                //}
            } else {
                if (null != sparqlQuery.getExtendedBindingNames()) {
                    String keyp = sparqlQuery.getExtendedBindingNames().get(key);
                    if (null != keyp) {
                        key = keyp;
                    }
                }
                solution.addBinding(key, value);
            }
        }

        // adding constants after filter application assumes that one will never filter on constants
        if (null != sparqlQuery.getConstants()) {
            for (Map.Entry<String, Value> e : sparqlQuery.getConstants().entrySet()) {
                solution.addBinding(e.getKey(), e.getValue());
            }
        }

        SparqlQuery.QueryForm form = sparqlQuery.getQueryForm();

        // note: SesameStream's response to an ASK query which evaluates to true is an empty BindingSet
        // A result of false is never produced, as data sources are assumed to be infinite streams
        if (SparqlQuery.QueryForm.SELECT == form) {
            if (sparqlQuery.getSequenceModifier().trySolution(solution, subscription)) {
                handleSolution(subscription.getHandler(), solution);
            }
        } else {
            throw new IllegalStateException("unexpected query form: " + form);
        }
    }

    protected BindingSet toBindingSet(final Bindings<Value> bindings) {

        MapBindingSet bs = new MapBindingSet();
        for (Map.Entry<String, Value> e : bindings.entrySet()) {
            bs.addBinding(e.getKey(), e.getValue());
        }

        return bs;
    }

    protected void increment(final Counter counter,
                           final boolean logChange) {
        if (SesameStream.getDoPerformanceMetrics()) {
            counter.increment();
            if (logChange) {
                logHasChanged = true;
            }
        }
    }

    private void logHeader() {
        if (SesameStream.getDoPerformanceMetrics()) {
            StringBuilder sb = new StringBuilder("LOG\ttime1,time2");
            for (Quantity q : counters.keySet()) {
                sb.append(",").append(q.name());
            }
            System.out.println(sb.toString());
        }
    }

    protected void logEntry() {
        if (SesameStream.getDoPerformanceMetrics()) {
            if (!SesameStream.getDoUseCompactLogFormat() || logHasChanged) {
                StringBuilder sb = new StringBuilder("LOG\t");
                sb.append(timeCurrentOperationBegan).append(",").append(clock.getTime());
                for (Map.Entry<Quantity, Counter> entry : counters.entrySet()) {
                    sb.append(",").append(entry.getValue().count);
                }
                System.out.println(sb.toString());

                logHasChanged = false;
            }
        }
    }

    private static String toString(final BindingSet b) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String n : b.getBindingNames()) {
            if (first) {
                first = false;
            } else {
                sb.append(", ");
            }

            sb.append(n).append(":").append(b.getValue(n));
        }

        return sb.toString();
    }

    protected void handleSolution(final BindingSetHandler handler,
                                final BindingSet solution) {
        increment(countSolutions, true);

        if (SesameStream.getDoPerformanceMetrics()) {
            System.out.println("SOLUTION\t" + clock.getTime() + "\t"
                    + QueryEngineImpl.toString(solution));
        }

        handler.handle(solution);
    }

    private static class Counter {
        private long count = 0;

        public void increment() {
            count++;
        }

        public void reset() {
            count = 0;
        }

        public long getCount() {
            return count;
        }
    }
}
