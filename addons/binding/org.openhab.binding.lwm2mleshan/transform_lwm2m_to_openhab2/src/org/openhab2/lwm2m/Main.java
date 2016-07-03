package org.openhab2.lwm2m;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.xml.sax.SAXException;

import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.XsltExecutable;
import net.sf.saxon.s9api.XsltTransformer;

public class Main {

    /**
     * Check readme.md file in the root directory. This application downloads OMA LWM2M Registry object files,
     * transforms those files to Openhab2 Things and Channels and stores those in an out directory. You need a
     * res directory with schema/thing-description-1.0.0.xsd, schema/LWM2M.xsd, transform/transform.xsl.
     *
     * @param args
     * @throws MalformedURLException
     * @throws InterruptedException
     */
    public static void main(String[] args) throws MalformedURLException, InterruptedException {
        System.out.println("Checking directories, schema files and load transformation file");

        File basePath = Paths.get("res").toAbsolutePath().toFile();
        File openhabSchemaFile = new File(basePath, "schema/thing-description-1.0.0.xsd");
        File lwm2mSchemaFile = new File(basePath, "schema/LWM2M.xsd");
        File transformFile = new File(basePath, "transform/transform.xsl");
        File lwm2mRegistryFiles = new File(basePath, "lwm2m_object_registry");
        File destPath = Paths.get("out").toAbsolutePath().toFile();

        if (!basePath.exists() || !lwm2mRegistryFiles.exists() || !transformFile.exists() || !openhabSchemaFile.exists()
                || !lwm2mSchemaFile.exists()) {
            System.err.println(
                    "Res directory or subdirectories does not exist in your working directory: " + basePath.toString());
            System.exit(-1);
        }

        if (!destPath.exists()) {
            destPath.mkdirs();
        }

        FilenameFilter filenameFilter = new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".xml");
            }
        };

        Processor processor = new Processor(false);
        XsltExecutable template = null;
        try {
            template = processor.newXsltCompiler().compile(new StreamSource(transformFile));
            if (template == null) {
                System.err.println("Failed to load transform.xsl");
                System.exit(-1);
                return;
            }
        } catch (SaxonApiException e) {
            e.printStackTrace();
            System.exit(-1);
            return;
        }

        final XsltExecutable templateFinal = template;

        // Setup input validator
        SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        Schema lwm2mSchema;
        try {
            lwm2mSchema = schemaFactory.newSchema(lwm2mSchemaFile);
        } catch (SAXException e) {
            e.printStackTrace();
            return;
        }

        try {
            System.out.println("Download OMA LWM2M Registry data");
            updateFiles(lwm2mRegistryFiles);
        } catch (IOException | InterruptedException e1) {
            System.err.println("Download of xml files failed " + e1.getMessage());
        }

        File[] files = lwm2mRegistryFiles.listFiles(filenameFilter);
        ExecutorService exec = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        List<Callable<Object>> todo = new ArrayList<Callable<Object>>(files.length);

        for (File xmlFile : files) {
            todo.add(Executors.callable(new Runnable() {
                @Override
                public void run() {
                    System.out.println("Transform " + xmlFile.getName().toString());
                    StreamSource streamSource = new StreamSource(xmlFile);

                    Validator validator = lwm2mSchema.newValidator();

                    try {
                        validator.validate(streamSource);
                    } catch (SAXException e) {
                        System.err.println("\tInput NOT valid");
                        System.err.println("Reason: " + e.getLocalizedMessage());
                        return;
                    } catch (IOException e) {
                        e.printStackTrace();
                        return;
                    }

                    XsltTransformer transformer = templateFinal.load();

                    try {
                        // Setup transformer
                        Serializer serializer = processor.newSerializer();
                        serializer.setOutputWriter(new StringWriter());
                        transformer.setDestination(serializer);
                        transformer.setBaseOutputURI(destPath.toURI().toURL().toString());
                        transformer.setInitialContextNode(processor.newDocumentBuilder().build(streamSource));
                        transformer.setSource(streamSource);
                        transformer.transform();
                    } catch (SaxonApiException e) {
                        e.printStackTrace();
                    } catch (MalformedURLException e) {
                        e.printStackTrace();
                    }
                }
            }));
        }

        exec.invokeAll(todo);
        exec.shutdown();

        validateOutput(openhabSchemaFile, destPath, filenameFilter);
        System.out.println("Done");
    }

    /**
     * Download web page, extract xml file links, download them in parallel, store them in the
     * given directory.
     *
     * @param lwm2mRegistryFiles The dest dir to store files.
     * @throws IOException
     * @throws InterruptedException
     */
    private static void updateFiles(File lwm2mRegistryFiles) throws IOException, InterruptedException {
        String data = downloadFile(
                "http://technical.openmobilealliance.org/Technical/technical-information/omna/lightweight-m2m-lwm2m-object-registry");

        Pattern linkPattern = Pattern.compile("<a[^>]+href=[\"']?([^\"'>]*\\.xml)[\"']?[^>]*>(.+?)</a>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher pageMatcher = linkPattern.matcher(data);
        Set<String> links = new TreeSet<String>();
        while (pageMatcher.find()) {
            links.add(pageMatcher.group(1));
        }

        ExecutorService exec = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        List<Callable<Object>> todo = new ArrayList<Callable<Object>>(links.size());

        for (String urlName : links) {
            todo.add(Executors.callable(new Runnable() {
                @Override
                public void run() {
                    String fileName = new File(urlName).getName();
                    String xmlFile;
                    try {
                        xmlFile = downloadFile(urlName);
                    } catch (IOException e) {
                        System.err.println("Failed to download " + urlName);
                        return;
                    }
                    if (xmlFile.length() > 0) {
                        // WORKAROUND for buggy 3200.xml
                        if (fileName.equals("3200.xml")) {
                            xmlFile = xmlFile.replace("-<", "<");
                        }
                        System.out.println("Downloaded " + fileName);
                        File destFile = new File(lwm2mRegistryFiles, fileName);
                        try (PrintWriter out = new PrintWriter(destFile)) {
                            out.println(xmlFile);
                        } catch (FileNotFoundException e) {
                            System.err.println(
                                    "Failed to store " + destFile.getAbsolutePath() + " " + e.getLocalizedMessage());
                        }

                    } else {
                        System.err.println("Failed to download " + urlName);
                    }
                }
            }));
        }
        exec.invokeAll(todo);
        exec.shutdown();
    }

    private static String downloadFile(String urlName) throws IOException {
        URL url = new URL(urlName);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setReadTimeout(2500);
        connection.setRequestProperty("User-Agent",
                "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1; .NET CLR 1.0.3705; .NET CLR 1.1.4322; .NET CLR 1.2.30703)");
        BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));

        StringBuilder response = new StringBuilder();
        String inputLine;

        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }

        in.close();

        return response.toString();
    }

    /**
     * Validate output files in parallel
     *
     * @param openhabSchemaFile
     * @param destPath
     * @param filenameFilter
     * @throws InterruptedException
     */
    private static void validateOutput(File openhabSchemaFile, File destPath, FilenameFilter filenameFilter)
            throws InterruptedException {
        // Validate
        SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        Schema schema;
        try {
            schema = schemaFactory.newSchema(openhabSchemaFile);
        } catch (SAXException e) {
            e.printStackTrace();
            return;
        }

        File[] files = destPath.listFiles(filenameFilter);

        ExecutorService exec = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        List<Callable<Object>> todo = new ArrayList<Callable<Object>>(files.length);

        for (File xmlFile : files) {
            todo.add(Executors.callable(new Runnable() {
                @Override
                public void run() {
                    System.out.println("Validate " + xmlFile.getName().toString());
                    Source xmlSource = new StreamSource(xmlFile);
                    try {
                        Validator validator = schema.newValidator();
                        validator.validate(xmlSource);
                    } catch (SAXException e) {
                        System.out.println("\tNOT valid");
                        System.out.println("Reason: " + e.getLocalizedMessage());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }));
        }

        exec.invokeAll(todo);
        exec.shutdown();
    }

}
