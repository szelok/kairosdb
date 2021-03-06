/*
 * Copyright 2013 Proofpoint Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.kairosdb.core.http.rest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.MalformedJsonException;
import com.google.inject.name.Named;
import org.kairosdb.core.DataPoint;
import org.kairosdb.core.DataPointSet;
import org.kairosdb.core.KairosDataPointFactory;
import org.kairosdb.core.datapoints.LongDataPointFactory;
import org.kairosdb.core.datapoints.LongDataPointFactoryImpl;
import org.kairosdb.core.datastore.DataPointGroup;
import org.kairosdb.core.datastore.DatastoreQuery;
import org.kairosdb.core.datastore.KairosDatastore;
import org.kairosdb.core.datastore.QueryMetric;
import org.kairosdb.core.formatter.DataFormatter;
import org.kairosdb.core.formatter.FormatterException;
import org.kairosdb.core.formatter.JsonFormatter;
import org.kairosdb.core.formatter.JsonResponse;
import org.kairosdb.core.http.rest.json.*;
import org.kairosdb.core.reporting.KairosMetricReporter;
import org.kairosdb.core.reporting.ThreadReporter;
import org.kairosdb.util.MemoryMonitorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static com.google.common.base.Preconditions.checkNotNull;
import static javax.ws.rs.core.Response.ResponseBuilder;

enum NameType
{
	METRIC_NAMES,
	TAG_KEYS,
	TAG_VALUES
}

@Path("/api/v1")
public class MetricsResource implements KairosMetricReporter
{
	public static final Logger logger = LoggerFactory.getLogger(MetricsResource.class);
	public static final String QUERY_TIME = "kairosdb.http.query_time";
	public static final String REQUEST_TIME = "kairosdb.http.request_time";
	public static final String INGEST_COUNT = "kairosdb.http.ingest_count";
	public static final String INGEST_TIME = "kairosdb.http.ingest_time";

	public static final String QUERY_URL = "/datapoints/query";

	private final KairosDatastore datastore;
	private final Map<String, DataFormatter> formatters = new HashMap<String, DataFormatter>();
	private final GsonParser gsonParser;

	//Used for parsing incomming metrices
	private final Gson gson;

	//These two are used to track rate of ingestion
	private final AtomicInteger m_ingestedDataPoints = new AtomicInteger();
	private final AtomicInteger m_ingestTime = new AtomicInteger();

	private final KairosDataPointFactory m_kairosDataPointFactory;

	@Inject
	private LongDataPointFactory m_longDataPointFactory = new LongDataPointFactoryImpl();

	@Inject
	@Named("HOSTNAME")
	private String hostName = "localhost";

	@Inject
	public MetricsResource(KairosDatastore datastore, GsonParser gsonParser,
			KairosDataPointFactory dataPointFactory)
	{
		this.datastore = checkNotNull(datastore);
		this.gsonParser = checkNotNull(gsonParser);
		m_kairosDataPointFactory = dataPointFactory;
		formatters.put("json", new JsonFormatter());

		GsonBuilder builder = new GsonBuilder();
		gson = builder.create();
	}

	private void setHeaders(ResponseBuilder responseBuilder)
	{
		responseBuilder.header("Access-Control-Allow-Origin", "*");
		responseBuilder.header("Pragma", "no-cache");
		responseBuilder.header("Cache-Control", "no-cache");
		responseBuilder.header("Expires", 0);
	}

	@GET
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path("/version")
	public Response getVersion()
	{
		Package thisPackage = getClass().getPackage();
		String versionString = thisPackage.getImplementationTitle() + " " + thisPackage.getImplementationVersion();
		ResponseBuilder responseBuilder = Response.status(Response.Status.OK).entity("{\"version\": \"" + versionString + "\"}");
		setHeaders(responseBuilder);
		return responseBuilder.build();
	}

	@GET
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path("/metricnames")
	public Response getMetricNames()
	{
		return executeNameQuery(NameType.METRIC_NAMES);
	}

	@GET
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path("/tagnames")
	public Response getTagNames()
	{
		return executeNameQuery(NameType.TAG_KEYS);
	}

	@GET
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path("/tagvalues")
	public Response getTagValues()
	{
		return executeNameQuery(NameType.TAG_VALUES);
	}

	@POST
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Consumes("application/gzip")
	@Path("/datapoints")
	public Response addGzip(InputStream gzip)
	{
		GZIPInputStream gzipInputStream;
		try
		{
			gzipInputStream = new GZIPInputStream(gzip);
		}
		catch (IOException e)
		{
			JsonResponseBuilder builder = new JsonResponseBuilder(Response.Status.BAD_REQUEST);
			return builder.addError(e.getMessage()).build();
		}
		return (add(gzipInputStream));
	}

	@POST
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path("/datapoints")
	public Response add(InputStream json)
	{
		try
		{
			JsonMetricParser parser = new JsonMetricParser(datastore, new InputStreamReader(json, "UTF-8"),
					gson, m_kairosDataPointFactory);
			ValidationErrors validationErrors = parser.parse();

			m_ingestedDataPoints.addAndGet(parser.getDataPointCount());
			m_ingestTime.addAndGet(parser.getIngestTime());

			if (!validationErrors.hasErrors())
				return Response.status(Response.Status.NO_CONTENT).build();
			else
			{
				JsonResponseBuilder builder = new JsonResponseBuilder(Response.Status.BAD_REQUEST);
				for (String errorMessage : validationErrors.getErrors())
				{
					builder.addError(errorMessage);
				}
				return builder.build();
			}
		}
		catch (JsonIOException e)
		{
			JsonResponseBuilder builder = new JsonResponseBuilder(Response.Status.BAD_REQUEST);
			return builder.addError(e.getMessage()).build();
		}
		catch (JsonSyntaxException e)
		{
			JsonResponseBuilder builder = new JsonResponseBuilder(Response.Status.BAD_REQUEST);
			return builder.addError(e.getMessage()).build();
		}
		catch (MalformedJsonException e)
		{
			JsonResponseBuilder builder = new JsonResponseBuilder(Response.Status.BAD_REQUEST);
			return builder.addError(e.getMessage()).build();
		}
		catch (Exception e)
		{
			logger.error("Failed to add metric.", e);
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage())).build();
		}
		catch (OutOfMemoryError e)
		{
			logger.error("Out of memory error.", e);
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage())).build();
		}
	}


	@POST
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path("/datapoints/query/tags")
	public Response getMeta(String json)
	{
		checkNotNull(json);
		logger.debug(json);

		try
		{
			File respFile = File.createTempFile("kairos", ".json", new File(datastore.getCacheDir()));
			BufferedWriter writer = new BufferedWriter(new FileWriter(respFile));

			JsonResponse jsonResponse = new JsonResponse(writer);

			jsonResponse.begin();

			List<QueryMetric> queries = gsonParser.parseQueryMetric(json);

			for (QueryMetric query : queries)
			{
				List<DataPointGroup> result = datastore.queryTags(query);

				try
				{
					jsonResponse.formatQuery(result, false, -1);
				}
				finally
				{
					for (DataPointGroup dataPointGroup : result)
					{
						dataPointGroup.close();
					}
				}
			}

			jsonResponse.end();
			writer.flush();
			writer.close();

			ResponseBuilder responseBuilder = Response.status(Response.Status.OK).entity(
					new FileStreamingOutput(respFile, false));

			setHeaders(responseBuilder);
			return responseBuilder.build();
		}
		catch (JsonSyntaxException e)
		{
			JsonResponseBuilder builder = new JsonResponseBuilder(Response.Status.BAD_REQUEST);
			return builder.addError(e.getMessage()).build();
		}
		catch (QueryException e)
		{
			JsonResponseBuilder builder = new JsonResponseBuilder(Response.Status.BAD_REQUEST);
			return builder.addError(e.getMessage()).build();
		}
		catch (BeanValidationException e)
		{
			JsonResponseBuilder builder = new JsonResponseBuilder(Response.Status.BAD_REQUEST);
			return builder.addErrors(e.getErrorMessages()).build();
		}
		catch (MemoryMonitorException e)
		{
			logger.error("Query failed.", e);
			System.gc();
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage())).build();
		}
		catch (Exception e)
		{
			logger.error("Query failed.", e);
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage())).build();
		}
		catch (OutOfMemoryError e)
		{
			logger.error("Out of memory error.", e);
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage())).build();
		}
	}

	private Response get(String json, boolean compress) throws Exception
	{		
		checkNotNull(json);
		logger.debug(json);

		ThreadReporter.setReportTime(System.currentTimeMillis());
		ThreadReporter.addTag("host", hostName);

		try
		{
			File respFile = File.createTempFile("kairos", ".json", new File(datastore.getCacheDir()));
			BufferedWriter writer = new BufferedWriter(new FileWriter(respFile));

			JsonResponse jsonResponse = new JsonResponse(writer);

			jsonResponse.begin();

			List<QueryMetric> queries = gsonParser.parseQueryMetric(json);

			int queryCount = 0;
			for (QueryMetric query : queries)
			{
				queryCount++;
				ThreadReporter.addTag("metric_name", query.getName());
				ThreadReporter.addTag("query_index", String.valueOf(queryCount));

				DatastoreQuery dq = datastore.createQuery(query);
				long startQuery = System.currentTimeMillis();

				try
				{
					List<DataPointGroup> results = dq.execute();
					jsonResponse.formatQuery(results, query.isExcludeTags(), dq.getSampleSize());

					ThreadReporter.addDataPoint(QUERY_TIME, System.currentTimeMillis() - startQuery);
				}
				finally
				{
					dq.close();
				}
			}

			jsonResponse.end();
			writer.flush();
			writer.close();

			ThreadReporter.clearTags();
			ThreadReporter.addTag("host", hostName);
			ThreadReporter.addTag("request", QUERY_URL);
			ThreadReporter.addDataPoint(REQUEST_TIME, System.currentTimeMillis() - ThreadReporter.getReportTime());

			ThreadReporter.submitData(m_longDataPointFactory, datastore);

			ResponseBuilder responseBuilder = Response.status(Response.Status.OK).entity(
					new FileStreamingOutput(respFile, compress));

			setHeaders(responseBuilder);
			return responseBuilder.build();
		}
		catch (JsonSyntaxException e)
		{
			JsonResponseBuilder builder = new JsonResponseBuilder(Response.Status.BAD_REQUEST);
			return builder.addError(e.getMessage()).build();
		}
		catch (QueryException e)
		{
			JsonResponseBuilder builder = new JsonResponseBuilder(Response.Status.BAD_REQUEST);
			return builder.addError(e.getMessage()).build();
		}
		catch (BeanValidationException e)
		{
			JsonResponseBuilder builder = new JsonResponseBuilder(Response.Status.BAD_REQUEST);
			return builder.addErrors(e.getErrorMessages()).build();
		}
		catch (MemoryMonitorException e)
		{
			logger.error("Query failed.", e);
			Thread.sleep(1000);
			System.gc();
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage())).build();
		}
		catch (Exception e)
		{
			logger.error("Query failed.", e);
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage())).build();
		}
		catch (OutOfMemoryError e)
		{
			logger.error("Out of memory error.", e);
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage())).build();
		}
		finally
		{
			ThreadReporter.clear();
		}
	}
	
	@POST
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path(QUERY_URL)
	public Response getWithoutCompression(String json) throws Exception
	{
		return get(json, false);
	}
	
	@POST
	@Produces("application/gzip; charset=UTF-8")
	@Path(QUERY_URL)
	public Response getWithCompression(String json) throws Exception
	{	
		return get(json, true);
	}

	@POST
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path("/datapoints/delete")
	public Response delete(String json) throws Exception
	{
		checkNotNull(json);
		logger.debug(json);

		try
		{
			List<QueryMetric> queries = gsonParser.parseQueryMetric(json);

			for (QueryMetric query : queries)
			{
				datastore.delete(query);
			}

			ResponseBuilder responseBuilder = Response.status(Response.Status.NO_CONTENT);
			responseBuilder.header("Access-Control-Allow-Origin", "*");
			return responseBuilder.build();
		}
		catch (JsonSyntaxException e)
		{
			JsonResponseBuilder builder = new JsonResponseBuilder(Response.Status.BAD_REQUEST);
			return builder.addError(e.getMessage()).build();
		}
		catch (QueryException e)
		{
			JsonResponseBuilder builder = new JsonResponseBuilder(Response.Status.BAD_REQUEST);
			return builder.addError(e.getMessage()).build();
		}
		catch (BeanValidationException e)
		{
			JsonResponseBuilder builder = new JsonResponseBuilder(Response.Status.BAD_REQUEST);
			return builder.addErrors(e.getErrorMessages()).build();
		}
		catch (MemoryMonitorException e)
		{
			logger.error("Query failed.", e);
			System.gc();
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage())).build();
		}
		catch (Exception e)
		{
			logger.error("Delete failed.", e);
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage())).build();
		}
		catch (OutOfMemoryError e)
		{
			logger.error("Out of memory error.", e);
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage())).build();
		}
	}

	private static ResponseBuilder getCorsPreflightResponseBuilder(final String requestHeaders,
			final String requestMethod)
	{
		ResponseBuilder responseBuilder = Response.status(Response.Status.OK);
		responseBuilder.header("Access-Control-Allow-Origin", "*");
		responseBuilder.header("Access-Control-Allow-Headers", requestHeaders);
		responseBuilder.header("Access-Control-Max-Age", "86400"); // Cache for one day
		if (requestMethod != null)
		{
			responseBuilder.header("Access-Control-Allow_Method", requestMethod);
		}

		return responseBuilder;
	}

	/**
	 Information for this endpoint was taken from https://developer.mozilla.org/en-US/docs/HTTP/Access_control_CORS.
	 <p/>
	 <p/>Response to a cors preflight request to access data.
	 */
	@OPTIONS
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path("/datapoints/query")
	public Response corsPreflightQuery(@HeaderParam("Access-Control-Request-Headers") final String requestHeaders,
			@HeaderParam("Access-Control-Request-Method") final String requestMethod)
	{
		ResponseBuilder responseBuilder = getCorsPreflightResponseBuilder(requestHeaders, requestMethod);
		return (responseBuilder.build());
	}

	@OPTIONS
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path("/datapoints/query/tags")
	public Response corsPreflightQueryTags(@HeaderParam("Access-Control-Request-Headers") final String requestHeaders,
			@HeaderParam("Access-Control-Request-Method") final String requestMethod)
	{
		ResponseBuilder responseBuilder = getCorsPreflightResponseBuilder(requestHeaders, requestMethod);
		return (responseBuilder.build());
	}

	@OPTIONS
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path("/datapoints")
	public Response corsPreflightDataPoints(@HeaderParam("Access-Control-Request-Headers") String requestHeaders,
	                                        @HeaderParam("Access-Control-Request-Method") String requestMethod)
	{
		ResponseBuilder responseBuilder = getCorsPreflightResponseBuilder(requestHeaders, requestMethod);
		return (responseBuilder.build());
	}

	@DELETE
	@Produces(MediaType.APPLICATION_JSON + "; charset=UTF-8")
	@Path("/metric/{metricName}")
	public Response metricDelete(@PathParam("metricName") String metricName) throws Exception
	{
		try
		{
			QueryMetric query = new QueryMetric(0L, Long.MAX_VALUE, 0, metricName);
			datastore.delete(query);

			ResponseBuilder responseBuilder = Response.status(Response.Status.NO_CONTENT);
			responseBuilder.header("Access-Control-Allow-Origin", "*");
			return responseBuilder.build();
		}
		catch (Exception e)
		{
			logger.error("Delete failed.", e);
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(new ErrorResponse(e.getMessage())).build();
		}
	}

	private Response executeNameQuery(NameType type)
	{
		try
		{
			Iterable<String> values = null;
			switch (type)
			{
				case METRIC_NAMES:
					values = datastore.getMetricNames();
					break;
				case TAG_KEYS:
					values = datastore.getTagNames();
					break;
				case TAG_VALUES:
					values = datastore.getTagValues();
					break;
			}

			DataFormatter formatter = formatters.get("json");

			ResponseBuilder responseBuilder = Response.status(Response.Status.OK).entity(
					new ValuesStreamingOutput(formatter, values));
			setHeaders(responseBuilder);
			return responseBuilder.build();
		}
		catch (Exception e)
		{
			logger.error("Failed to get " + type, e);
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(
					new ErrorResponse(e.getMessage())).build();
		}
	}

	@Override
	public List<DataPointSet> getMetrics(long now)
	{
		int time = m_ingestTime.getAndSet(0);
		int count = m_ingestedDataPoints.getAndSet(0);

		if (count == 0)
			return Collections.EMPTY_LIST;

		DataPointSet dpsCount = new DataPointSet(INGEST_COUNT);
		DataPointSet dpsTime = new DataPointSet(INGEST_TIME);

		dpsCount.addTag("host", hostName);
		dpsTime.addTag("host", hostName);

		dpsCount.addDataPoint(m_longDataPointFactory.createDataPoint(now, count));
		dpsTime.addDataPoint(m_longDataPointFactory.createDataPoint(now, time));
		List<DataPointSet> ret = new ArrayList<DataPointSet>();
		ret.add(dpsCount);
		ret.add(dpsTime);

		return ret;
	}

	public class ValuesStreamingOutput implements StreamingOutput
	{
		private DataFormatter m_formatter;
		private Iterable<String> m_values;

		public ValuesStreamingOutput(DataFormatter formatter, Iterable<String> values)
		{
			m_formatter = formatter;
			m_values = values;
		}

		@SuppressWarnings("ResultOfMethodCallIgnored")
		public void write(OutputStream output) throws IOException, WebApplicationException
		{
			Writer writer = new OutputStreamWriter(output, "UTF-8");

			try
			{
				m_formatter.format(writer, m_values);
			}
			catch (FormatterException e)
			{
				logger.error("Description of what failed:", e);
			}

			writer.flush();
		}
	}

	public class FileStreamingOutput implements StreamingOutput
	{
		private File m_responseFile;
		private boolean m_compress;

		public FileStreamingOutput(File responseFile, boolean zip)
		{
			m_responseFile = responseFile;
			m_compress = zip;
		}

		@SuppressWarnings("ResultOfMethodCallIgnored")
		@Override
		public void write(OutputStream output) throws IOException, WebApplicationException
		{
			try
			{
				if (m_compress) {
					output = new GZIPOutputStream(output);
				}
				
				InputStream reader = new FileInputStream(m_responseFile);

				byte[] buffer = new byte[1024];
				int size;

				while ((size = reader.read(buffer)) != -1)
				{
					output.write(buffer, 0, size);
				}

				reader.close();
				
				if (m_compress) {
					((GZIPOutputStream) output).finish();
				}
				output.flush();
			}
			finally
			{
				m_responseFile.delete();
			}
		}
	}
}
