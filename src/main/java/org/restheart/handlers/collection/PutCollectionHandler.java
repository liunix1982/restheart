/*
 * RESTHeart - the Web API for MongoDB
 * Copyright (C) SoftInstigate Srl
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.restheart.handlers.collection;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import java.util.List;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.restheart.db.Database;
import org.restheart.db.OperationResult;
import org.restheart.hal.metadata.InvalidMetadataException;
import org.restheart.hal.metadata.Relationship;
import org.restheart.hal.metadata.RepresentationTransformer;
import org.restheart.hal.metadata.RequestChecker;
import org.restheart.handlers.PipedHttpHandler;
import org.restheart.handlers.RequestContext;
import org.restheart.handlers.injectors.LocalCachesSingleton;
import org.restheart.utils.HttpStatus;
import org.restheart.utils.ResponseHelper;

/**
 *
 * @author Andrea Di Cesare {@literal <andrea@softinstigate.com>}
 */
public class PutCollectionHandler extends PipedHttpHandler {

    /**
     * Creates a new instance of PutCollectionHandler
     */
    public PutCollectionHandler() {
        super();
    }

    /**
     * Creates a new instance of PutCollectionHandler
     *
     * @param next
     */
    public PutCollectionHandler(PipedHttpHandler next) {
        super(next);
    }

    /**
     * Creates a new instance of PutCollectionHandler
     *
     * @param next
     * @param dbsDAO
     */
    public PutCollectionHandler(PipedHttpHandler next, Database dbsDAO) {
        super(next, dbsDAO);
    }

    /**
     *
     * @param exchange
     * @param context
     * @throws Exception
     */
    @Override
    public void handleRequest(
            HttpServerExchange exchange,
            RequestContext context) throws Exception {
        BsonValue _content = context.getContent();

        if (_content == null) {
            _content = new BsonDocument();
        }

        // cannot PUT an array
        if (!_content.isDocument()) {
            ResponseHelper.endExchangeWithMessage(
                    exchange,
                    context,
                    HttpStatus.SC_NOT_ACCEPTABLE,
                    "data must be a json object");
            return;
        }

        BsonDocument content = _content.asDocument();

        // check RELS metadata
        if (content.containsKey(Relationship.RELATIONSHIPS_ELEMENT_NAME)) {
            try {
                Relationship.getFromJson(content);
            } catch (InvalidMetadataException ex) {
                ResponseHelper.endExchangeWithMessage(
                        exchange,
                        context,
                        HttpStatus.SC_NOT_ACCEPTABLE,
                        "wrong relationships definition. " + ex.getMessage()
                        , ex);
                return;
            }
        }

        // check RT metadata
        if (content.containsKey(RepresentationTransformer.RTS_ELEMENT_NAME)) {
            try {
                RepresentationTransformer.getFromJson(content);
            } catch (InvalidMetadataException ex) {
                ResponseHelper.endExchangeWithMessage(
                        exchange,
                        context,
                        HttpStatus.SC_NOT_ACCEPTABLE,
                        "wrong representation transformer definition. "
                        + ex.getMessage(),
                        ex);
                return;
            }
        }

        // check SC metadata
        if (content.containsKey(RequestChecker.ROOT_KEY)) {
            try {
                RequestChecker.getFromJson((BsonDocument)content);
            } catch (InvalidMetadataException ex) {
                ResponseHelper.endExchangeWithMessage(
                        exchange,
                        context,
                        HttpStatus.SC_NOT_ACCEPTABLE,
                        "wrong schema checker definition. "
                        + ex.getMessage(),
                        ex);
                return;
            }
        }

        boolean updating = context.getCollectionProps() != null;

        OperationResult result = getDatabase().upsertCollection(
                context.getDBName(),
                context.getCollectionName(),
                content,
                context.getETag(),
                updating, false,
                context.isETagCheckRequired());

        context.setDbOperationResult(result);

        // invalidate the cache collection item
        LocalCachesSingleton.getInstance().invalidateCollection(
                context.getDBName(),
                context.getCollectionName());

        // inject the etag
        if (result.getEtag() != null) {
            ResponseHelper.injectEtagHeader(exchange, result.getEtag());
        }

        if (result.getHttpCode() == HttpStatus.SC_CONFLICT) {
            ResponseHelper.endExchangeWithMessage(
                    exchange,
                    context,
                    HttpStatus.SC_CONFLICT,
                    "The collection's ETag must be provided using the '"
                    + Headers.IF_MATCH
                    + "' header.");
            return;
        }

        // send the warnings if any (and in case no_content change the return code to ok
        if (context.getWarnings() != null
                && !context.getWarnings().isEmpty()) {
            sendWarnings(result.getHttpCode(), exchange, context);
        } else {
            exchange.setStatusCode(result.getHttpCode());
        }

        if (getNext() != null) {
            getNext().handleRequest(exchange, context);
        }

        exchange.endExchange();
    }
}
