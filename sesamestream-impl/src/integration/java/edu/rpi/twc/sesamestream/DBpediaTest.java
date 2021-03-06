package edu.rpi.twc.sesamestream;

import edu.rpi.twc.sesamestream.etc.QueryEngineTestBase;
import edu.rpi.twc.sesamestream.impl.QueryEngineImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openrdf.model.Statement;
import org.openrdf.query.algebra.TupleExpr;
import org.openrdf.sail.memory.MemoryStore;

import java.util.List;

/**
 * @author Joshua Shinavier (http://fortytwo.net)
 */
public class DBpediaTest extends QueryEngineTestBase {

    @Before
    public void setUp() throws Exception {
        sail = new MemoryStore();
        sail.initialize();

        queryEngine = new QueryEngineImpl();
    }

    @After
    public void tearDown() throws Exception {
        sail.shutDown();
    }

    @Test
    public void testQuery3() throws Exception {
        TupleExpr q3 = loadQuery("dbpedia-q3.rq");

        queryEngine.addQuery(QUERY_TTL, q3, simpleBindingSetHandler);

        List<Statement> l = loadData("/tmp/dbpedia-singlefile-randomized-100000.nt");
        long i = 0;
        long max = 100000;
        for (Statement st : l) {
            if (i++ >= max) {
                break;
            }

            queryEngine.addStatements(TUPLE_TTL, st);
        }

        //queryEngine.getIndex().print();
    }
}
