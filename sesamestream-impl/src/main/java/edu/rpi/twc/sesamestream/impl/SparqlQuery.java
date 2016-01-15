package edu.rpi.twc.sesamestream.impl;

import edu.rpi.twc.sesamestream.QueryEngine;
import edu.rpi.twc.sesamestream.core.LList;
import edu.rpi.twc.sesamestream.core.Term;
import org.openrdf.model.Value;
import org.openrdf.query.algebra.DescribeOperator;
import org.openrdf.query.algebra.Distinct;
import org.openrdf.query.algebra.Exists;
import org.openrdf.query.algebra.Extension;
import org.openrdf.query.algebra.ExtensionElem;
import org.openrdf.query.algebra.Filter;
import org.openrdf.query.algebra.Join;
import org.openrdf.query.algebra.Modify;
import org.openrdf.query.algebra.Not;
import org.openrdf.query.algebra.Order;
import org.openrdf.query.algebra.Projection;
import org.openrdf.query.algebra.ProjectionElem;
import org.openrdf.query.algebra.ProjectionElemList;
import org.openrdf.query.algebra.QueryModelNode;
import org.openrdf.query.algebra.Reduced;
import org.openrdf.query.algebra.Slice;
import org.openrdf.query.algebra.StatementPattern;
import org.openrdf.query.algebra.TupleExpr;
import org.openrdf.query.algebra.ValueConstant;
import org.openrdf.query.algebra.ValueExpr;
import org.openrdf.query.algebra.Var;
import org.openrdf.query.algebra.helpers.TupleExprs;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * An internal representation of a SPARQL query
 *
 * @author Joshua Shinavier (http://fortytwo.net)
 */
public class SparqlQuery {
    private static final Logger logger = Logger.getLogger(SparqlQuery.class.getName());

    // note: preserves order of variables for the sake of ordering solution bindings accordingly
    protected final LinkedHashSet<String> bindingNames = new LinkedHashSet<String>();

    protected Map<String, String> extendedBindingNames;
    protected LList<Term<Value>[]> triplePatterns;
    protected List<Filter> filters;
    protected Map<String, Value> constants;

    protected final SolutionSequenceModifier sequenceModifier = new SolutionSequenceModifier();

    /**
     * Any of the five SPARQL query forms
     */
    public enum QueryForm {
        ASK, CONSTRUCT, DESCRIBE, SELECT, MODIFY
    }

    protected QueryForm queryForm;

    public SparqlQuery(final TupleExpr expr)
            throws QueryEngine.IncompatibleQueryException {
        init(expr);
    }

    public SparqlQuery(final QueryModelNode node)
            throws QueryEngine.IncompatibleQueryException {
        init(node);
    }

    protected void init(final QueryModelNode node)
            throws QueryEngine.IncompatibleQueryException {

        triplePatterns = LList.NIL;

        List<QueryModelNode> l = visit(node);
        if (l.size() != 1) {
            throw new QueryEngine.IncompatibleQueryException("multiple root nodes");
        }
        QueryModelNode root = l.iterator().next();

        // TODO: eliminate redundant patterns
        Collection<StatementPattern> patterns = new LinkedList<StatementPattern>();

        queryForm = findQueryType(root);

        if (QueryForm.SELECT == queryForm) {
            findPatternsInRoot(root, patterns);
        } else {
            throw new QueryEngine.IncompatibleQueryException(queryForm.name()
                    + " query form is currently not supported");
        }

        for (StatementPattern pat : patterns) {
            triplePatterns = triplePatterns.push(toNative(pat));
        }
    }

    protected Term<Value>[] toNative(StatementPattern sp) {
        // note: assumes tupleSize==3
        return new Term[]{
                toNative(sp.getSubjectVar()),
                toNative(sp.getPredicateVar()),
                toNative(sp.getObjectVar())};
    }

    private Term<Value> toNative(Var v) {
        return v.hasValue()
                ? new Term<Value>(v.getValue(), null)
                : new Term<Value>(null, v.getName());
    }

    /**
     * @return the query form of this query (ASK, CONSTRUCT, DESCRIBE, or SELECT)
     */
    public QueryForm getQueryForm() {
        return queryForm;
    }

    /**
     * @return any predefined bindings which are to be added to query solutions.
     *         For example, CONSTRUCT queries may bind constants to the subject, predicate, or object variable
     */
    public Map<String, Value> getConstants() {
        return constants;
    }

    protected static QueryForm findQueryType(final QueryModelNode root) throws QueryEngine.IncompatibleQueryException {
        if (root instanceof Slice) {
            // note: ASK queries also have Slice as root in Sesame, but we treat them as SELECT queries
            return QueryForm.SELECT;
        } else if (root instanceof Reduced) {
            // note: CONSTRUCT queries also have Reduced as root in Sesame, but this is because they have
            // been transformed to SELECT queries for {?subject, ?predicate, ?object}.
            // We simply treat them as SELECT queries.
            return QueryForm.SELECT;
        } else if (root instanceof Projection || root instanceof Distinct) {
            return QueryForm.SELECT;
        } else if (root instanceof DescribeOperator) {
            return QueryForm.DESCRIBE;
        } else if (root instanceof Modify) {
            return QueryForm.MODIFY;
        } else {
            throw new QueryEngine.IncompatibleQueryException("could not infer type of query from root node: " + root);
        }
    }

    private void addExtendedBindingName(final String from,
                                        final String to) {
        // projections of x onto x happen quite often; save some space
        if (from.equals(to)) {
            return;
        }

        if (null == extendedBindingNames) {
            extendedBindingNames = new HashMap<String, String>();
        }

        extendedBindingNames.put(from, to);
    }

    public LList<Term<Value>[]> getTriplePatterns() {
        return triplePatterns;
    }

    /**
     * Gets the order-preserving list of variable names
     * @return the order-preserving list of variable names
     */
    public Collection<String> getBindingNames() {
        return bindingNames;
    }

    public Map<String, String> getExtendedBindingNames() {
        return extendedBindingNames;
    }

    public List<Filter> getFilters() {
        return filters;
    }

    /**
     * @return an object which represents this query's DISTINCT/REDUCED, OFFSET, and LIMIT behavior
     */
    public SolutionSequenceModifier getSequenceModifier() {
        return sequenceModifier;
    }

    protected void findPatternsInRoot(final QueryModelNode root,
                                    final Collection<StatementPattern> patterns)
            throws QueryEngine.IncompatibleQueryException {

        if (root instanceof Projection) {
            findPatterns((Projection) root, patterns);
        } else if (root instanceof Join) {
            findPatterns((Join) root, patterns);
        } else if (root instanceof Filter) {
            findPatterns((Filter) root, patterns);
        } else if (root instanceof Distinct) {
            sequenceModifier.makeDistinct();

            List<QueryModelNode> l = visitChildren(root);
            if (1 != l.size()) {
                throw new QueryEngine.IncompatibleQueryException("exactly one node expected beneath DISTINCT");
            }

            findPatternsInRoot(l.get(0), patterns);
        } else if (root instanceof Reduced) {
            sequenceModifier.makeReduced();

            List<QueryModelNode> l = visitChildren(root);
            if (1 != l.size()) {
                throw new QueryEngine.IncompatibleQueryException("exactly one node expected beneath DISTINCT");
            }

            findPatternsInRoot(l.get(0), patterns);
        } else if (root instanceof Slice) {
            Slice s = (Slice) root;
            if (s.hasLimit()) {
                sequenceModifier.setLimit(s.getLimit());
            }
            if (s.hasOffset()) {
                sequenceModifier.setOffset(s.getOffset());
            }

            List<QueryModelNode> l = visitChildren(root);
            if (1 != l.size()) {
                throw new QueryEngine.IncompatibleQueryException("exactly one node expected beneath Slice");
            }

            findPatternsInRoot(l.get(0), patterns);
        } else {
            throw new QueryEngine.IncompatibleQueryException(
                    "expected Projection or Distinct at root node of query; found " + root);
        }
    }

    protected void findPatterns(final StatementPattern p,
                              final Collection<StatementPattern> patterns) {
        patterns.add(p);
    }

    protected void findPatterns(final Join j,
                              final Collection<StatementPattern> patterns)
            throws QueryEngine.IncompatibleQueryException {

        for (QueryModelNode n : visitChildren(j)) {
            if (n instanceof StatementPattern) {
                findPatterns((StatementPattern) n, patterns);
            } else if (n instanceof Join) {
                findPatterns((Join) n, patterns);
            } else {
                throw new QueryEngine.IncompatibleQueryException("unexpected node: " + n);
            }
        }
    }

    protected void findPatterns(final Filter f,
                              final Collection<StatementPattern> patterns)
            throws QueryEngine.IncompatibleQueryException {

        if (null == filters) {
            filters = new LinkedList<Filter>();
        }
        filters.add(f);

        List<QueryModelNode> filterChildren = visitChildren(f);
        if (2 != filterChildren.size()) {
            throw new QueryEngine.IncompatibleQueryException("expected exactly two nodes beneath filter");
        }

        QueryModelNode valueExpr = filterChildren.get(0);
        if (!(valueExpr instanceof ValueExpr)) {
            throw new QueryEngine.IncompatibleQueryException(
                    "expected value expression as first child of filter; found " + valueExpr);
        }

        checkFilterFunctionSupported((ValueExpr) valueExpr);

        QueryModelNode filterChild = filterChildren.get(1);
        if (filterChild instanceof Join) {
            findPatterns((Join) filterChild, patterns);
        } else if (filterChild instanceof StatementPattern) {
            findPatterns((StatementPattern) filterChild, patterns);
        } else {
            if (filterChild instanceof Filter) {
                Filter childFilter = (Filter) filterChild;
                ValueExpr ve = childFilter.getCondition();
            }

            throw new QueryEngine.IncompatibleQueryException(
                    "expected join or statement pattern beneath filter; found " + filterChild);
        }
    }

    protected void checkFilterFunctionSupported(final ValueExpr expr) throws QueryEngine.IncompatibleQueryException {
        if (expr instanceof Not) {
            List<QueryModelNode> children = visitChildren(expr);
            if (1 != children.size()) {
                throw new QueryEngine.IncompatibleQueryException("expected exactly one node beneath NOT");
            }

            QueryModelNode valueExpr = children.get(0);
            if (!(valueExpr instanceof ValueExpr)) {
                throw new QueryEngine.IncompatibleQueryException(
                        "expected value expression as first child of NOT; found " + valueExpr);
            }

            checkFilterFunctionSupported((ValueExpr) valueExpr);
        } else {
            // EXISTS is specifically not (yet) supported; all other filter functions are assumed to be supported
            if (expr instanceof Exists) {
                throw new QueryEngine.IncompatibleQueryException("EXISTS and NOT EXISTS are not supported");
            }
        }
    }

    protected void findPatterns(final Projection p,
                              final Collection<StatementPattern> patterns)
            throws QueryEngine.IncompatibleQueryException {

        List<QueryModelNode> l = visitChildren(p);

        Extension ext = null;

        for (QueryModelNode n : l) {
            if (n instanceof Extension) {
                ext = (Extension) n;
            } else if (n instanceof ProjectionElemList) {
                ProjectionElemList pl = (ProjectionElemList) n;
                for (ProjectionElem pe : pl.getElements()) {
                    bindingNames.add(pe.getSourceName());
                    addExtendedBindingName(pe.getSourceName(), pe.getTargetName());
                }
            }
        }

        if (null != ext) {
            l = visitChildren(ext);
        }

        for (QueryModelNode n : l) {
            if (n instanceof Join) {
                Join j = (Join) n;
                if (TupleExprs.containsProjection(j)) {
                    throw new QueryEngine.IncompatibleQueryException("join contains projection");
                }

                findPatterns(j, patterns);
            } else if (n instanceof StatementPattern) {
                findPatterns((StatementPattern) n, patterns);
            } else if (n instanceof Filter) {
                findPatterns((Filter) n, patterns);
            } else if (n instanceof ProjectionElemList) {
                // TODO: remind self when these are encountered and why they are ignored
                //LOGGER.info("ignoring " + n);
            } else if (n instanceof ExtensionElem) {
                ExtensionElem ee = (ExtensionElem) n;

                ValueExpr ve = ee.getExpr();
                if (ve instanceof ValueConstant) {
                    String name = ee.getName();
                    String target = extendedBindingNames.get(name);

                    if (null == target) {
                        throw new QueryEngine.IncompatibleQueryException(
                                "ExtensionElem does not correspond to a projection variable");
                    }

                    ValueConstant vc = (ValueConstant) ve;
                    if (null == constants) {
                        constants = new HashMap<String, Value>();
                    }
                    constants.put(target, vc.getValue());
                } else if (ve instanceof Var) {
                    // do nothing; the source-->target mapping is already in the extended binding names
                } else {
                    throw new QueryEngine.IncompatibleQueryException(
                            "expected ValueConstant or Var within ExtensionElem; found " + ve);
                }
            } else if (n instanceof Order) {
                throw new QueryEngine.IncompatibleQueryException(
                        "the ORDER BY modifier is not supported by SesameStream");
            } else {
                throw new QueryEngine.IncompatibleQueryException("unexpected type: " + n.getClass());
            }
        }
    }

    protected List<QueryModelNode> visit(final QueryModelNode node) {
        List<QueryModelNode> visited = new LinkedList<QueryModelNode>();
        SimpleQueryModelVisitor v = new SimpleQueryModelVisitor(visited);

        try {
            node.visit(v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        //for (QueryModelNode n : visited) {
        //    System.out.println("node: " + n);
        //}

        return visited;
    }

    protected List<QueryModelNode> visitChildren(final QueryModelNode node) {
        List<QueryModelNode> visited = new LinkedList<QueryModelNode>();
        SimpleQueryModelVisitor v = new SimpleQueryModelVisitor(visited);

        try {
            node.visitChildren(v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        /*
        for (QueryModelNode n : visited) {
            System.out.println("node: " + n);
        }
        //*/

        return visited;
    }

}
