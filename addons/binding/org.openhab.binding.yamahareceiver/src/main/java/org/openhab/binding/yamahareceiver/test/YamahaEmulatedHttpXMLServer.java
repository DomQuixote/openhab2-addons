package org.openhab.binding.yamahareceiver.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.openhab.binding.yamahareceiver.internal.protocol.HttpXMLSendReceive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * Emulates a Yamaha Receiver API endpoint (Http/XML on localhost:12121/YamahaRemoteControl/ctrl)
 *
 */
public class YamahaEmulatedHttpXMLServer implements Runnable {
    private Logger logger = LoggerFactory.getLogger(YamahaEmulatedHttpXMLServer.class);
    private final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    private boolean stopServer = false;
    private final int port;

    public static String parseBodyXML(InputStream inputStream, int len) throws IOException {
        StringBuffer sb = new StringBuffer();
        while (--len >= 0) {
            int charRead = inputStream.read();
            if (charRead == -1) {
                break;
            }

            if ((char) charRead == '\r') { // if we've got a '\r'
                inputStream.read(); // ignore '\n'
                break;
            }
            sb.append((char) charRead);
        }

        String data = sb.toString();
        return data;
    }

    public static String parseRequestURL(InputStream inputStream) throws IOException {
        StringBuffer sb = new StringBuffer();
        while (true) {
            int charRead = inputStream.read();
            if (charRead == -1) {
                break;
            }

            if ((char) charRead == '\r') { // if we've got a '\r'
                inputStream.read(); // ignore '\n'
                break;
            }
            sb.append((char) charRead);
        }

        String data = sb.toString();
        if (data.length() < 12) {
            return "";
        }
        return data.substring(data.indexOf(' ') + 1, data.length() - 9);

    }

    public static Map<String, String> parseHTTPHeaders(InputStream inputStream) throws IOException {
        int charRead;
        StringBuffer sb = new StringBuffer();
        while (true) {
            sb.append((char) (charRead = inputStream.read()));
            if ((char) charRead == '\r') { // if we've got a '\r'
                sb.append((char) inputStream.read()); // then write '\n'
                charRead = inputStream.read(); // read the next char;
                if (charRead == '\r') { // if it's another '\r'
                    sb.append((char) inputStream.read());// write the '\n'
                    break;
                } else {
                    sb.append((char) charRead);
                }
            }
        }

        String data = sb.toString();
        String[] headersArray = data.split("\r\n");
        // GET /YamahaRemoteControl/ctrl HTTP/1.1\r\n
        Map<String, String> headers = new HashMap();
        for (int i = 0; i < headersArray.length; i++) {
            String[] parts = headersArray[i].split(": ");
            if (parts.length != 2) {
                continue;
            }
            headers.put(parts[0], parts[1]);
        }

        return headers;
    }

    public YamahaEmulatedHttpXMLServer(int port) {
        this.port = port;
        new Thread(this).start();
    }

    @Override
    public void run() {
        try {
            logger.debug("Started emulated Yamaha Server");
            ServerSocket server;
            server = new ServerSocket(port);
            System.out.println("Listening for connection on port 8080 ....");
            while (!stopServer) {
                try (Socket socket = server.accept()) {
                    InputStream is = socket.getInputStream();
                    String request = parseRequestURL(is);
                    if (request == null || !request.equals("/YamahaRemoteControl/ctrl")) {
                        continue;
                    }
                    int len = Integer.valueOf(parseHTTPHeaders(is).get("Content-Length"));
                    String bodyXML = parseBodyXML(is, len);
                    logger.debug("Received request: " + (bodyXML.contains("GET") ? bodyXML : "PUT"));

                    String responseXML = "";
                    try {
                        DocumentBuilder db = dbf.newDocumentBuilder();
                        Document doc = db.parse(new InputSource(new StringReader(bodyXML)));
                        String nodeName = doc.getFirstChild().hasChildNodes() == false ? null
                                : doc.getFirstChild().getNodeName();
                        if (nodeName == null || !nodeName.equals("YAMAHA_AV")) {
                            logger.debug("Could not handle response");
                        } else {
                            // We are not sending back a valid xml document. The
                            // header "<?xml version=\"1.0\" encoding=\"utf-8\"?>" is missing for Yamaha AVR responses.
                            responseXML = parse(doc.getFirstChild());
                            String httpResponse = "HTTP/1.1 200 OK\r\nContent-type: text/xml\r\nContent-length: "
                                    + String.valueOf(responseXML.length()) + "\r\n\r\n" + responseXML;
                            socket.getOutputStream().write(httpResponse.getBytes("UTF-8"));
                            socket.getOutputStream().flush();
                        }
                    } catch (Exception e) {
                        logger.debug("Could not handle response: " + e.getMessage());
                        String httpResponse = "HTTP/1.1 404 OK\r\nContent-type:text/xml\r\n\r\n";
                        socket.getOutputStream().write(httpResponse.getBytes("UTF-8"));
                        socket.getOutputStream().flush();
                    }

                    socket.close();
                }
            }
            server.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String parse(Node firstChild) throws Exception {
        String value = firstChild.getAttributes().getNamedItem("cmd").getNodeValue();
        switch (value) {
            case "GET":
                return "<YAMAHA_AV rsp=\"GET\" RC=\"0\">" + parseGET(firstChild.getFirstChild())
                        + HttpXMLSendReceive.XML_END;
            case "PUT":
                return "<YAMAHA_AV rsp=\"PUT\" RC=\"0\">" + parsePUT(firstChild.getFirstChild())
                        + HttpXMLSendReceive.XML_END;
            default:
                throw new Exception("Test parser expected GET/PUT for cmd attribute");
        }
    }

    private String parsePUT(Node node) throws Exception {
        StringWriter writer = new StringWriter();
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.transform(new DOMSource(node), new StreamResult(writer));
        String xml = writer.toString();
        logger.debug("PUT " + xml);
        return "";
    }

    private String parseGET(Node firstChild) throws Exception {
        switch (firstChild.getNodeName()) {
            case "System": {
                Node child = firstChild.getFirstChild();
                if (!child.getTextContent().equals("GetParam")) {
                    throw new Exception("Node for system/ only supports GetParam");
                }
                switch (child.getNodeName()) {
                    case "Config":
                        return "<System><Config><Model_Name>Test AVR</Model_Name><System_ID>1234</System_ID><Version>1.0</Version><Feature_Existence><Main_Zone>1</Main_Zone><Zone_2>1</Zone_2><Zone_3>0</Zone_3></Feature_Existence></Config></System>";
                    case "Power_Control":
                        return "<System><Power_Control><Power>On</Power></Power_Control></System>";
                    default:
                        throw new Exception("Node for system/ not supported");
                }
            }
            case "NET_RADIO":
                Node child = firstChild.getFirstChild();
                if (!child.getTextContent().equals("GetParam")) {
                    throw new Exception("Node for Play_Info/ only supports GetParam");
                }
                if (child.getNodeName().equals("Play_Info")) {
                    return "<NET_RADIO>" + playInfo() + "</NET_RADIO>";
                } else if (child.getNodeName().equals("List_Info")) {
                    return "<NET_RADIO>" + listInfo() + "</NET_RADIO>";
                } else if (child.getNodeName().equals("Play_Control")) {
                    return "<NET_RADIO>" + playControl() + "</NET_RADIO>";
                } else {
                    throw new Exception("Node for NET_RADIO only supports Play_Info and List_Info");
                }
            case "Main_Zone":
                return "<Main_Zone>" + parseZone(firstChild.getFirstChild()) + "</Main_Zone>";
            case "Zone_2":
                return "<Zone_2>" + parseZone(firstChild.getFirstChild()) + "</Zone_2>";
            default:
                throw new Exception("Node for GET not supported");
        }
    }

    private String playControl() {
        // Play_Control/Preset/Preset_Sel
        return "<Play_Control><Preset><Preset_Sel>1</Preset_Sel></Preset></Play_Control>";
    }

    private String playInfo() {
        return "<Play_Info><Playback_Info>Play</Playback_Info><Meta_Info><Station>TestStation</Station><Artist>TestArtist</Artist><Album>TestAlbum</Album><Song>TestSong</Song></Meta_Info></Play_Info>";
    }

    private String listInfo() {
        return "<List_Info><Menu_Status>Ready</Menu_Status><Menu_Name>Testname</Menu_Name><Menu_Layer>2</Menu_Layer><Cursor_Position><Current_Line>1</Current_Line><Max_Line>1</Max_Line></Cursor_Position><Current_List><Line_1><Txt>Eintrag1</Txt></Line_1></Current_List></List_Info>";
    }

    private String parseZone(Node firstChild) throws Exception {
        String nodeName = firstChild.getNodeName();
        switch (nodeName) {
            case "Basic_Status": {
                Node child = firstChild.getFirstChild();
                if (!child.getTextContent().equals("GetParam")) {
                    throw new Exception("Node for Basic_Status/ only supports GetParam");
                }
                return "<Basic_Status><Power_Control><Power>On</Power></Power_Control><Input><Input_Sel>Net Radio</Input_Sel><Input_Sel_Item_Info><Src_Name>NET_RADIO</Src_Name></Input_Sel_Item_Info></Input><Surround><Program_Sel><Current><Sound_Program>7ch Stereo</Sound_Program></Current></Program_Sel></Surround><Volume><Mute>Off</Mute><Lvl><Val>100</Val></Lvl></Volume></Basic_Status>";
            }
            case "Input": {
                firstChild = firstChild.getFirstChild();
                if (firstChild.getNodeName().equals("Input_Sel_Item")) {
                    Node child = firstChild.getFirstChild();
                    if (!child.getTextContent().equals("GetParam")) {
                        throw new Exception("Node for Input/Input_Sel_Item/ only supports GetParam");
                    }
                    return "<Input><Input_Sel_Item><Item1><Param>HDMI 1</Param><RW>RW</RW></Item1><Item2><Param>Net Radio</Param><RW>RW</RW></Item2></Input_Sel_Item></Input>";
                }
            }
            case "List_Info": {
                Node child = firstChild.getFirstChild();
                if (!child.getTextContent().equals("GetParam")) {
                    throw new Exception("Node for List_Info/ only supports GetParam");
                }
                return listInfo();
            }
            case "Play_Info": {
                Node child = firstChild.getFirstChild();
                if (!child.getTextContent().equals("GetParam")) {
                    throw new Exception("Node for Play_Info/ only supports GetParam");
                }
                return playInfo();
            }
            case "Play_Control": {
                Node child = firstChild.getFirstChild();
                if (!child.getTextContent().equals("GetParam")) {
                    throw new Exception("Node for Play_Control/ only supports GetParam");
                }
                return playControl();
            }
        }

        throw new Exception("Node for zone not supported");
    }
}
