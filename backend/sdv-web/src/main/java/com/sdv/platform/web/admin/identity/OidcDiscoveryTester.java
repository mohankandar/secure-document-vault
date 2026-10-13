package com.sdv.platform.web.admin.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class OidcDiscoveryTester {
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    public OidcDiscoveryTester(ObjectMapper mapper){this.mapper=mapper;}

    public TestResult test(String issuerValue){
        try {
            URI issuer=validateIssuer(issuerValue);
            URI discovery=URI.create(normalize(issuer)+"/.well-known/openid-configuration");
            HttpRequest request=HttpRequest.newBuilder(discovery).timeout(Duration.ofSeconds(5)).header("Accept","application/json").GET().build();
            HttpResponse<String> response=client.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)return new TestResult(false,"OIDC discovery returned HTTP "+response.statusCode()+".");
            JsonNode json=mapper.readTree(response.body());
            String discoveredIssuer=text(json,"issuer");
            if(!normalize(issuer).equals(normalize(URI.create(discoveredIssuer))))return new TestResult(false,"OIDC discovery issuer does not match the configured issuer.");
            validateEndpoint(text(json,"authorization_endpoint")); validateEndpoint(text(json,"token_endpoint")); validateEndpoint(text(json,"jwks_uri"));
            return new TestResult(true,"OIDC discovery and required endpoints verified.");
        } catch(InterruptedException ex){Thread.currentThread().interrupt();return new TestResult(false,"OIDC connection test was interrupted.");}
        catch(Exception ex){return new TestResult(false,safeFailure(ex));}
    }
    private static URI validateIssuer(String value){
        if(value==null||value.isBlank())throw new IllegalArgumentException("Issuer is required."); URI uri=URI.create(value.trim());
        if(uri.getScheme()==null||uri.getHost()==null)throw new IllegalArgumentException("Issuer must be an absolute HTTP(S) URI.");
        if(!uri.getScheme().equalsIgnoreCase("https")&&!uri.getScheme().equalsIgnoreCase("http"))throw new IllegalArgumentException("Issuer must use HTTP or HTTPS.");
        if(uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("Issuer must not contain credentials, query parameters, or fragments.");
        if(uri.getScheme().equalsIgnoreCase("http")&&!isLoopback(uri.getHost()))throw new IllegalArgumentException("HTTP issuers are allowed only for loopback local development.");
        return uri;
    }
    private static void validateEndpoint(String value){URI uri=URI.create(value);if(!uri.isAbsolute()||(!"https".equalsIgnoreCase(uri.getScheme())&&!"http".equalsIgnoreCase(uri.getScheme())))throw new IllegalArgumentException("OIDC discovery contains an invalid endpoint.");}
    private static String text(JsonNode json,String field){JsonNode value=json.get(field);if(value==null||!value.isTextual()||value.asText().isBlank())throw new IllegalArgumentException("OIDC discovery is missing "+field+".");return value.asText();}
    private static boolean isLoopback(String host){return "localhost".equalsIgnoreCase(host)||"127.0.0.1".equals(host)||"::1".equals(host)||"[::1]".equals(host);}
    private static String normalize(URI uri){String s=uri.toString().trim();return s.endsWith("/")?s.substring(0,s.length()-1):s;}
    private static String safeFailure(Exception ex){String type=ex instanceof IllegalArgumentException?"Invalid OIDC configuration":"OIDC discovery could not be verified";String m=ex.getMessage();return m==null||m.isBlank()?type+".":type+": "+(m.length()>300?m.substring(0,300):m);}
    public record TestResult(boolean passed,String message){}
}
