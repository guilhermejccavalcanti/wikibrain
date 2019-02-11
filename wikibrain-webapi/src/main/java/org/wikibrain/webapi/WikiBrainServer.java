package org.wikibrain.webapi;

import org.apache.commons.cli.*;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikibrain.conf.ConfigurationException;
import org.wikibrain.conf.DefaultOptionBuilder;
import org.wikibrain.core.cmd.Env;
import org.wikibrain.core.cmd.EnvBuilder;
import org.wikibrain.core.dao.DaoException;
import org.wikibrain.core.dao.LocalPageDao;
import org.wikibrain.core.lang.Language;
import org.wikibrain.core.model.LocalLink;
import org.wikibrain.core.model.LocalPage;
import org.wikibrain.core.model.Title;
import org.wikibrain.sr.SRMetric;
import org.wikibrain.sr.SRResult;
import org.wikibrain.sr.SRResultList;
import org.wikibrain.sr.wikify.Wikifier;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Shilad Sen
 */
public class WikiBrainServer extends AbstractHandler {
    private static final Logger LOG = LoggerFactory.getLogger(WikiBrainServer.class);
    private final Env env;
    private final LocalPageDao pageDao;
    private WebEntityParser entityParser;

    public WikiBrainServer(Env env) throws ConfigurationException {
        this.env = env;
        this.entityParser = new WebEntityParser(env);
        this.pageDao = env.getConfigurator().get(LocalPageDao.class);
    }

    @Override
    public void handle(String target, Request request, HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws IOException, ServletException {
        WikiBrainWebRequest req = new WikiBrainWebRequest(target, request, httpServletRequest, httpServletResponse);
        LOG.info("received request for {}?{}", request.getRequestURL(), request.getQueryString());

        try {
            // TODO: add logging
            if (target.equals("/similarity")) {
                doSimilarity(req);
            } else if (target.equals("/cosimilarity")) {
                throw new UnsupportedOperationException();
            } else if (target.equals("/mostSimilar")) {
                doMostSimilar(req);
            } else if (target.equals("/wikify")) {
                doWikify(req);
            } else if (target.equals("/pageRank")) {
                doPageRank(req);
            }
        } catch (WikiBrainWebException e) {
            req.writeError(e);
        } catch (ConfigurationException e) {
            req.writeError(e);
        } catch (DaoException e) {
            req.writeError(e);
        }
    }

    private void doSimilarity(WikiBrainWebRequest req) throws ConfigurationException, DaoException {
        // TODO: support explanations
        Language lang = req.getLanguage();
        List<WebEntity> entities = entityParser.extractEntityList(req);
        if (entities.size() != 2) {
            throw new WikiBrainWebException("Similarity requires exactly two entities");
        }
        WebEntity entity1 = entities.get(0);
        WebEntity entity2 = entities.get(1);
        SRMetric sr = env.getConfigurator().get(SRMetric.class, "milnewitten", "language", lang.getLangCode());
        SRResult r = null;
        switch (entity1.getType()) {
            case ARTICLE_ID: case TITLE:
                r = sr.similarity(entity1.getArticleId(), entity2.getArticleId(), false);
                break;
            case PHRASE:
                r = sr.similarity(entity1.getPhrase(), entity2.getPhrase(), false);
                break;
            default:
                throw new WikiBrainWebException("Unsupported entity type: " + entity1.getType());
        }
        Double sim = (r != null && r.isValid()) ? r.getScore() : null;
        req.writeJsonResponse("score", sim, "entity1", entity1.toJson(), "entity2", entity2.toJson());
    }

    private void doMostSimilar(WikiBrainWebRequest req) throws DaoException, ConfigurationException {
        Language lang = req.getLanguage();
        WebEntity entity = entityParser.extractEntity(req);
        int n = Integer.valueOf(req.getParam("n", "10"));
        SRMetric sr = env.getConfigurator().get(SRMetric.class, "milnewitten", "language", lang.getLangCode());
        SRResultList results;
        switch (entity.getType()) {
            case ARTICLE_ID: case TITLE:
                results = sr.mostSimilar(entity.getArticleId(), n);
                break;
            case PHRASE:
                results = sr.mostSimilar(entity.getPhrase(), n);
                break;
            default:
                throw new WikiBrainWebException("Unsupported entity type: " + entity.getType());
        }
        List jsonResults = new ArrayList();
        for (SRResult r : results) {
            LocalPage page = pageDao.getById(lang, r.getId());
            Map obj = new HashMap();
            obj.put("articleId", r.getId());
            obj.put("score", r.getScore());
            obj.put("lang", lang.getLangCode());
            obj.put("title", page == null ? "Unknown" : page.getTitle().getCanonicalTitle());
            jsonResults.add(obj);
        }
        req.writeJsonResponse("results", jsonResults);
    }

    private void doPageRank(WikiBrainWebRequest req) throws ConfigurationException, DaoException {

    }

    private void doWikify(WikiBrainWebRequest req) throws ConfigurationException, DaoException {
        Language lang = req.getLanguage();
        Wikifier wf = env.getConfigurator().get(Wikifier.class, "websail", "language", lang.getLangCode());
        String text = req.getParamOrDie("text");
        List jsonConcepts = new ArrayList();
        for (LocalLink ll : wf.wikify(text)) {
            LocalPage page = pageDao.getById(lang, ll.getDestId());
            Map obj = new HashMap();
            obj.put("index", ll.getLocation());
            obj.put("text", ll.getAnchorText());
            obj.put("lang", lang.getLangCode());
            obj.put("articleId", ll.getDestId());
            obj.put("title", page == null ? "Unknown" : page.getTitle().getCanonicalTitle());
            jsonConcepts.add(obj);
        }
        req.writeJsonResponse("text", text, "references", jsonConcepts);
    }

    public static void main(String args[]) throws Exception {
        Options options = new Options();
        options.addOption(
                new DefaultOptionBuilder()
                        .withLongOpt("port")
                        .withDescription("Server port number")
                        .create("p"));
        options.addOption(
                new DefaultOptionBuilder()
                        .withLongOpt("listeners")
                        .withDescription("Size of listener queue")
                        .create("q"));

        EnvBuilder.addStandardOptions(options);

        CommandLineParser parser = new PosixParser();
        CommandLine cmd;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.err.println( "Invalid option usage: " + e.getMessage());
            new HelpFormatter().printHelp("DumpLoader", options);
            return;
        }

        Env env = new EnvBuilder(cmd).build();

        int port = Integer.valueOf(cmd.getOptionValue("p", "8000"));
        int queueSize = Integer.valueOf(cmd.getOptionValue("q", "100"));
        Server server = new Server(new QueuedThreadPool(queueSize, 20));
        server.setHandler(new WikiBrainServer(env));
        ServerConnector sc = new ServerConnector(server);
        sc.setPort(port);
        server.setConnectors(new Connector[]{sc});
        server.start();
        server.join();
    }
}
