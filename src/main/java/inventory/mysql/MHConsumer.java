package inventory.mysql;

import java.util.Properties;
import java.util.Arrays;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import inventory.mysql.rest.RESTAdmin;
import org.apache.tomcat.jni.Error;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

public class MHConsumer {

    private KafkaConsumer<String, String> consumer;
    private String topic;
    private String message;
    private String servers;
    private String username;
    private String password;
    private String admin_rest_url;
    private String api_key;
    private ElasticSearch es;
    private Config config;

    // Constructor
    public MHConsumer() {
        // Get config object
        config = new Config();

        // Assign topic and message
        topic = config.mh_topic;
        if (topic == null || topic.equals("")) {
            topic = "api";
        }

        message = config.mh_message;
        if (message == null || message.equals("")) {
            message = "refresh_cache";
        }

        // Assign username and password
        username = config.mh_user;
        password = config.mh_password;
        servers = config.mh_kafka_brokers_sasl;
        admin_rest_url = config.mh_kafka_admin_url;
        api_key = config.mh_api_key;

        // Setup ElasticSearch class
        es = new ElasticSearch();

        try {

            // Set JAAS config
            set_jaas_configuration();

            Properties props = new Properties();
            props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            props.put("group.id", "inventory-group");
            props.put("client.id", "inventory-id");
            props.put("security.protocol", "SASL_SSL");
            props.put("sasl.mechanism", "PLAIN");
            props.put("ssl.protocol", "TLSv1.2");
            props.put("sl.enabled.protocols", "TLSv1.2");
            props.put("ssl.endpoint.identification.algorithm", "HTTPS");
            props.put("auto.offset.reset", "latest");
            props.put("bootstrap.servers", servers);

            // Get topics to see if our topic exists
            String topics_string = RESTAdmin.listTopics(admin_rest_url, api_key);
            System.out.println("Admin REST Listing Topics: " + topics_string);

            // Check if topic exist
            JSONArray topics = new JSONArray(topics_string);
            boolean create_topic = true;

            for (int i = 0; i < topics.length(); i++) {
                JSONObject t = topics.getJSONObject(i);
                String t_name = t.getString("name");

                if (t_name.equals(topic)) {
                    System.out.println("Topic " + topic + " already exists!");
                    create_topic = false;
                    break;
                }
            }

            // Create topic if it does not exist
            if (create_topic) {
                System.out.println("Creating the topic " + topic);
                String restResponse = RESTAdmin.createTopic(admin_rest_url, api_key, topic);
                String error = new JSONObject(restResponse).getString("errorMessage");

                if (error != null || error.equals("") != false) {
                    throw new Exception(error);
                }

                System.out.println("Successfully created the topic");
            }

            consumer = new KafkaConsumer<String, String>(props);
            System.out.println("Created Kafka Consumer");

        } catch (Exception e) {
            System.out.println("Exception occurred, application will terminate:");
            System.out.println(e);
            e.printStackTrace();
            System.exit(-1);
        }
    }

    // Subscribe to topic and start polling for messages
    // If message is found, then it refreshes the cache
    public void subscribe() {
        consumer.subscribe(Arrays.asList(topic));

        while (true) {
            ConsumerRecords<String, String> records = consumer.poll(3000);
            for (ConsumerRecord<String, String> record : records) {
                System.out.printf("Message = %s\n", record.value());

                // Check if message matches the trigger
                if (record.value().toLowerCase().contains(message.toLowerCase())) {
                    System.out.println("Got the right message! Refreshing cache!\n\n");
                    es.refresh_cache();
                }
            }
        }
    }

    // Creates JAAS configuration file to interact with Kafka servers securely
    // Also sets path to configuration file in Java properties
    private void set_jaas_configuration() throws IOException {
        /*
            This is what the jass.conf file should look like

            KafkaClient {
                org.apache.kafka.common.security.plain.PlainLoginModule required
                serviceName="kafka"
                username="USERNAME"
                password="PASSWORD";
            };
        */

        // Create JAAS file path
        String jaas_file_path = System.getProperty("java.io.tmpdir") + "jaas.conf";

        // Set JAAS file path in Java settings
        System.setProperty("java.security.auth.login.config", jaas_file_path);

        // Build JAAS file contents
        StringBuilder jaas = new StringBuilder();
        jaas.append("KafkaClient {\n");
        jaas.append("\torg.apache.kafka.common.security.plain.PlainLoginModule required\n");
        jaas.append("\tserviceName=\"kafka\"\n");
        jaas.append(String.format("\tusername=\"%s\"\n", username));
        jaas.append(String.format("\tpassword=\"%s\";\n", password));
        jaas.append("};");

        // Write to JAAS file
        OutputStream jaasOutStream = null;

        try {
            jaasOutStream = new FileOutputStream(jaas_file_path, false);
            jaasOutStream.write(jaas.toString().getBytes(Charset.forName("UTF-8")));
            System.out.println("Successfully wrote to JAAS configuration file");

        } catch (final IOException e) {
            System.out.println("Error: Failed accessing to JAAS config file:");
            System.out.println(e);
            throw e;
        } finally {
            if (jaasOutStream != null) {
                try {
                    jaasOutStream.close();
                    System.out.println("Closed JAAS file");
                } catch (final Exception e) {
                    System.out.println("Error closing generated JAAS config file:");
                    System.out.println(e);
                }
            }
        }
    }
}