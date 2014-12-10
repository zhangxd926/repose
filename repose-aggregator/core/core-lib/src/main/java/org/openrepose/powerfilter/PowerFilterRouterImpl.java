package org.openrepose.powerfilter;

import org.openrepose.commons.utils.StringUtilities;
import org.openrepose.commons.utils.http.HttpStatusCode;
import org.openrepose.commons.utils.io.stream.ReadLimitReachedException;
import org.openrepose.commons.utils.servlet.http.MutableHttpServletRequest;
import org.openrepose.commons.utils.servlet.http.MutableHttpServletResponse;
import org.openrepose.commons.utils.servlet.http.RouteDestination;
import org.openrepose.core.RequestTimeout;
import org.openrepose.core.ResponseCode;
import org.openrepose.core.filter.logic.DispatchPathBuilder;
import org.openrepose.core.filter.routing.DestinationLocation;
import org.openrepose.core.filter.routing.DestinationLocationBuilder;
import org.openrepose.core.services.headers.response.ResponseHeaderService;
import org.openrepose.core.services.reporting.ReportingService;
import org.openrepose.core.services.reporting.metrics.MeterByCategory;
import org.openrepose.core.services.reporting.metrics.MetricsService;
import org.openrepose.core.services.reporting.metrics.impl.MeterByCategorySum;
import org.openrepose.core.services.routing.RoutingService;
import org.openrepose.core.systemmodel.*;
import org.openrepose.nodeservice.request.RequestHeaderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static java.net.HttpURLConnection.HTTP_CLIENT_TIMEOUT;

/**
 * This class routes a request to the appropriate endpoint specified in system-model.cfg.xml and receives
 * a response.
 * <p/>
 * The final URI is constructed from the following information:
 * - TODO
 * <p/>
 * This class also instruments the response codes coming from the endpoint.
 * <p/>
 */
@Named
public class PowerFilterRouterImpl implements PowerFilterRouter {

    private static final Logger LOG = LoggerFactory.getLogger(PowerFilterRouterImpl.class);
    private final Map<String, Destination> destinations;
    private final ReportingService reportingService;
    private final RequestHeaderService requestHeaderService;
    private final ResponseHeaderService responseHeaderService;
    private final RoutingService routingService;
    private ServletContext context;
    private ReposeCluster domain;
    private String defaultDst;
    private DestinationLocationBuilder locationBuilder;
    private Map<String, MeterByCategory> mapResponseCodes = new HashMap<>();
    private Map<String, MeterByCategory> mapRequestTimeouts = new HashMap<>();
    private MeterByCategory mbcAllResponse;
    private MeterByCategory mbcAllTimeouts;

    private MetricsService metricsService;

    //TODO: maybe use spring to inject the servlet context into here
    @Inject
    public PowerFilterRouterImpl(
            MetricsService metricsService,
            ReportingService reportingService,
            RequestHeaderService requestHeaderService,
            ResponseHeaderService responseHeaderService,
            RoutingService routingService) {
        LOG.info("Creating Repose Router");

        this.routingService = routingService;
        this.destinations = new HashMap<>();
        this.reportingService = reportingService;
        this.responseHeaderService = responseHeaderService;
        this.requestHeaderService = requestHeaderService;

        if (metricsService != null && metricsService.isEnabled()) {
            this.metricsService = metricsService;
        }
    }

    @Override
    public void initialize(ReposeCluster domain, Node localhost, ServletContext context, String defaultDst) throws PowerFilterChainException {
        if (localhost == null || domain == null) {
            throw new PowerFilterChainException("Domain and localhost cannot be null");
        }

        LOG.info("Initializing Repose Router");
        this.domain = domain;
        this.context = context;
        this.defaultDst = defaultDst;
        this.destinations.clear();

        //Set up location builder
        locationBuilder = new DestinationLocationBuilder(routingService, localhost);

        if (domain.getDestinations() != null) {
            addDestinations(domain.getDestinations().getEndpoint());
            addDestinations(domain.getDestinations().getTarget());
        }

        if (metricsService != null) {
            mbcAllResponse = metricsService.newMeterByCategory(ResponseCode.class,
                    "All Endpoints",
                    "Response Codes",
                    TimeUnit.SECONDS);
            mbcAllTimeouts = metricsService.newMeterByCategory(RequestTimeout.class,
                    "TimeoutToOrigin",
                    "Request Timeout",
                    TimeUnit.SECONDS);
        }
    }

    private void addDestinations(List<? extends Destination> destList) {
        for (Destination dest : destList) {
            destinations.put(dest.getId(), dest);
        }
    }

    @Override
    public void route(MutableHttpServletRequest servletRequest, MutableHttpServletResponse servletResponse) throws IOException, ServletException, URISyntaxException {
        DestinationLocation location = null;

        if (!StringUtilities.isBlank(defaultDst)) {
            servletRequest.addDestination(defaultDst, servletRequest.getRequestURI(), -1);

        }
        RouteDestination routingDestination = servletRequest.getDestination();
        String rootPath = "";

        Destination configDestinationElement = null;

        if (routingDestination != null) {
            configDestinationElement = destinations.get(routingDestination.getDestinationId());
            if (configDestinationElement == null) {
                LOG.warn("Invalid routing destination specified: " + routingDestination.getDestinationId() + " for domain: " + domain.getId());
                ((HttpServletResponse) servletResponse).setStatus(HttpStatusCode.NOT_FOUND.intValue());
            } else {
                location = locationBuilder.build(configDestinationElement, routingDestination.getUri(), servletRequest);

                rootPath = configDestinationElement.getRootPath();
            }
        }

        if (location != null) {
            // According to the Java 6 javadocs the routeDestination passed into getContext:
            // "The given path [routeDestination] must begin with /, is interpreted relative to the server's document root
            // and is matched against the context roots of other web applications hosted on this container."
            final ServletContext targetContext = context.getContext(location.getUri().toString());

            if (targetContext != null) {
                // Capture this for Location header processing
                final HttpServletRequest originalRequest = (HttpServletRequest) servletRequest.getRequest();

                String uri = new DispatchPathBuilder(location.getUri().getPath(), targetContext.getContextPath()).build();
                final RequestDispatcher dispatcher = targetContext.getRequestDispatcher(uri);

                servletRequest.setRequestUrl(new StringBuffer(location.getUrl().toExternalForm()));
                servletRequest.setRequestUri(location.getUri().getPath());
                requestHeaderService.setVia(servletRequest);
                requestHeaderService.setXForwardedFor(servletRequest);
                if (dispatcher != null) {
                    LOG.debug("Attempting to route to " + location.getUri());
                    LOG.debug("Request URL: " + ((HttpServletRequest) servletRequest).getRequestURL());
                    LOG.debug("Request URI: " + ((HttpServletRequest) servletRequest).getRequestURI());
                    LOG.debug("Context path = " + targetContext.getContextPath());

                    final long startTime = System.currentTimeMillis();
                    try {
                        reportingService.incrementRequestCount(routingDestination.getDestinationId());
                        dispatcher.forward(servletRequest, servletResponse);

                        // track response code for endpoint & across all endpoints
                        String endpoint = getEndpoint(configDestinationElement, location);
                        MeterByCategory mbc = verifyGet(endpoint);
                        MeterByCategory mbcTimeout = getTimeoutMeter(endpoint);

                        PowerFilter.markResponseCodeHelper(mbc, servletResponse.getStatus(), LOG, endpoint);
                        PowerFilter.markResponseCodeHelper(mbcAllResponse, servletResponse.getStatus(), LOG, MeterByCategorySum.ALL);
                        markRequestTimeoutHelper(mbcTimeout, servletResponse.getStatus(), endpoint);
                        markRequestTimeoutHelper(mbcAllTimeouts, servletResponse.getStatus(), "All Endpoints");

                        final long stopTime = System.currentTimeMillis();
                        reportingService.recordServiceResponse(routingDestination.getDestinationId(), servletResponse.getStatus(), stopTime - startTime);
                        responseHeaderService.fixLocationHeader(originalRequest, servletResponse, routingDestination, location.getUri().toString(), rootPath);
                    } catch (IOException e) {
                        if (e.getCause() instanceof ReadLimitReachedException) {
                            LOG.error("Error reading request content", e);
                            servletResponse.sendError(HttpStatusCode.REQUEST_ENTITY_TOO_LARGE.intValue(), "Error reading request content");
                            servletResponse.setLastException(e);
                        } else {
                            LOG.error("Connection Refused to " + location.getUri() + " " + e.getMessage(), e);
                            ((HttpServletResponse) servletResponse).setStatus(HttpStatusCode.SERVICE_UNAVAIL.intValue());
                        }
                    }
                }
            }
        }
    }

    private String getEndpoint(Destination dest, DestinationLocation location) {

        StringBuilder sb = new StringBuilder();

        sb.append(location.getUri().getHost() + ":" + location.getUri().getPort());

        if (dest instanceof DestinationEndpoint) {

            sb.append(((DestinationEndpoint) dest).getRootPath());
        } else if (dest instanceof DestinationCluster) {

            sb.append(((DestinationCluster) dest).getRootPath());
        } else {
            throw new IllegalArgumentException("Unknown destination type: " + dest.getClass().getName());
        }

        return sb.toString();
    }

    private MeterByCategory verifyGet(String endpoint) {
        if (metricsService == null) {
            return null;
        }

        if (!mapResponseCodes.containsKey(endpoint)) {
            synchronized (mapResponseCodes) {


                if (!mapResponseCodes.containsKey(endpoint)) {

                    mapResponseCodes.put(endpoint, metricsService.newMeterByCategory(ResponseCode.class,
                            endpoint,
                            "Response Codes",
                            TimeUnit.SECONDS));
                }
            }
        }

        return mapResponseCodes.get(endpoint);
    }

    private MeterByCategory getTimeoutMeter(String endpoint) {
        if (metricsService == null) {
            return null;
        }

        if (!mapRequestTimeouts.containsKey(endpoint)) {
            synchronized (mapRequestTimeouts) {
                if (!mapRequestTimeouts.containsKey(endpoint)) {
                    mapRequestTimeouts.put(endpoint, metricsService.newMeterByCategory(RequestTimeout.class,
                            "TimeoutToOrigin",
                            "Request Timeout",
                            TimeUnit.SECONDS));
                }
            }
        }

        return mapRequestTimeouts.get(endpoint);
    }

    public void markRequestTimeoutHelper(MeterByCategory mbc, int responseCode, String endpoint) {
        if (mbc == null) {
            return;
        }

        if (responseCode == HTTP_CLIENT_TIMEOUT) {
            mbc.mark(endpoint);
        }
    }
}
