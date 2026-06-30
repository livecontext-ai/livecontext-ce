// Utility function to validate and filter headers
export const getValidHeaders = (headers: any[]): any[] => {
  if (!headers || headers.length === 0) return [];
  
  return headers.filter(header => 
    header.name && 
    header.name.trim() !== '' && 
    header.value && 
    header.value.trim() !== ''
  );
};

// Utility function to build the complete URL
export const getFullUrl = (endpoint: string, baseUrl: string): string => {
  if (!endpoint || !baseUrl) return endpoint || baseUrl || '';

  const cleanBaseUrl = baseUrl.replace(/\/$/, ''); // Remove trailing slash
  const cleanEndpoint = endpoint.replace(/^\//, ''); // Remove leading slash

  return `${cleanBaseUrl}/${cleanEndpoint}`;
};

// Utility function to build URL parameters
export const buildUrlParams = (parameters: any[]): string => {
  if (!parameters || parameters.length === 0) return '';
  
  const validParams = parameters.filter(p => p.name && p.name.trim() !== '');
  if (validParams.length === 0) return '';
  
  return validParams
    .map(param => {
      // Use example if available, otherwise default value based on type
      let value = param.example;
      if (!value || value.trim() === '') {
        switch (param.type) {
          case 'string':
            value = 'example_value';
            break;
          case 'number':
            value = '123';
            break;
          case 'boolean':
            value = 'true';
            break;
          case 'object':
            value = '{"key":"value"}';
            break;
          case 'array':
            value = '["item1","item2"]';
            break;
          default:
            value = 'example_value';
        }
      }
      return `${encodeURIComponent(param.name)}=${encodeURIComponent(value)}`;
    })
    .join('&');
};

// Utility function to build body from bodyParams
export const buildEncodedBody = (bodyParams: any[]): string => {
  if (!bodyParams || bodyParams.length === 0) return '';

  return bodyParams
      .filter(p => p.name && p.value)
      .map(p => `${encodeURIComponent(p.name)}=${encodeURIComponent(p.value)}`)
      .join('&');
};

// Utility function to build JSON body from bodyParams
export const buildJsonBody = (bodyParams: any[]): any => {
  if (!bodyParams || bodyParams.length === 0) return {};

  const body: any = {};
  bodyParams
      .filter(p => p.name && p.value)
      .forEach(p => {
        body[p.name] = p.value;
      });

  return body;
};

// Code generation functions for previews
export const generateCurlCommand = (tool: any, apiConfig: any): string => {
  const fullUrl = getFullUrl(tool.endpoint, apiConfig.baseUrl);
  const urlParams = buildUrlParams(tool.parameters || []);
  const finalUrl = urlParams ? `${fullUrl}?${urlParams}` : fullUrl;
  
  let curl = `curl -X ${tool.method} "${finalUrl}"`;
  
  if (apiConfig.authorization.type !== 'none') {
    if (apiConfig.authorization.type === 'bearer') {
      const tokenValue = apiConfig.authorization.headerValue || 'YOUR_TOKEN';
      // Remove "Bearer " prefix if it's already in the value
      const cleanToken = tokenValue.startsWith('Bearer ') ? tokenValue.substring(7) : tokenValue;
      curl += ` \\\n  -H "Authorization: Bearer ${cleanToken}"`;
    } else if (apiConfig.authorization.type === 'basic') {
      const username = apiConfig.authorization.username || 'YOUR_USERNAME';
      const password = apiConfig.authorization.password || 'YOUR_PASSWORD';
      curl += ` \\\n  -u "${username}:${password}"`;
    } else {
      const tokenValue = apiConfig.authorization.headerValue || 'YOUR_TOKEN';
      curl += ` \\\n  -H "${apiConfig.authorization.headerName}: ${tokenValue}"`;
    }
  }
  
  // Specific tool headers
  const validHeaders = getValidHeaders(tool.headers || []);
  if (validHeaders.length > 0) {
    validHeaders.forEach((header: any) => {
      curl += ` \\\n  -H "${header.name}: ${header.value}"`;
    });
  }
  
  // Body for POST/PUT
  if ((tool.method === 'POST' || tool.method === 'PUT') && tool.bodyParams && tool.bodyParams.length > 0) {
    const bodyData = buildEncodedBody(tool.bodyParams);
    if (bodyData) {
      curl += ` \\\n  -d "${bodyData}"`;
    }
  }
  
  return curl;
};

export const generateJavaScriptCode = (tool: any, apiConfig: any): string => {
  const fullUrl = getFullUrl(tool.endpoint, apiConfig.baseUrl);
  const urlParams = buildUrlParams(tool.parameters || []);
  const finalUrl = urlParams ? `${fullUrl}?${urlParams}` : fullUrl;
  
  let code = `const url = "${finalUrl}";\n\n`;
  
  // Headers
  code += `const headers = {\n`;
  if (apiConfig.authorization.type !== 'none') {
    if (apiConfig.authorization.type === 'bearer') {
      const tokenValue = apiConfig.authorization.headerValue || 'YOUR_TOKEN';
      // Remove "Bearer " prefix if it's already in the value
      const cleanToken = tokenValue.startsWith('Bearer ') ? tokenValue.substring(7) : tokenValue;
      code += `  'Authorization': 'Bearer ${cleanToken}',\n`;
    } else if (apiConfig.authorization.type === 'basic') {
      const username = apiConfig.authorization.username || 'YOUR_USERNAME';
      const password = apiConfig.authorization.password || 'YOUR_PASSWORD';
      code += `  'Authorization': 'Basic ' + btoa('${username}:${password}'),\n`;
    } else {
      const tokenValue = apiConfig.authorization.headerValue || 'YOUR_TOKEN';
      code += `  '${apiConfig.authorization.headerName}': '${tokenValue}',\n`;
    }
  }
  
  const validHeaders = getValidHeaders(tool.headers || []);
  if (validHeaders.length > 0) {
    validHeaders.forEach((header: any) => {
      code += `  '${header.name}': '${header.value}',\n`;
    });
  }
  code += `};\n\n`;
  
  // Body
  if ((tool.method === 'POST' || tool.method === 'PUT') && tool.bodyParams && tool.bodyParams.length > 0) {
    const bodyData = buildJsonBody(tool.bodyParams);
    code += `const body = ${JSON.stringify(bodyData, null, 2)};\n\n`;
  }
  
  // Requete
  code += `fetch(url, {\n`;
  code += `  method: '${tool.method}',\n`;
  code += `  headers: headers`;
  if ((tool.method === 'POST' || tool.method === 'PUT') && tool.bodyParams && tool.bodyParams.length > 0) {
    code += `,\n  body: JSON.stringify(body)`;
  }
  code += `\n})\n`;
  code += `  .then(response => response.json())\n`;
  code += `  .then(data => console.log(data))\n`;
  code += `  .catch(error => console.error('Error:', error));`;
  
  return code;
};

export const generatePythonCode = (tool: any, apiConfig: any): string => {
  const fullUrl = getFullUrl(tool.endpoint, apiConfig.baseUrl);
  const urlParams = buildUrlParams(tool.parameters || []);
  const finalUrl = urlParams ? `${fullUrl}?${urlParams}` : fullUrl;
  
  let code = `import requests\nimport base64\n\n`;
  code += `url = "${finalUrl}"\n\n`;
  
  // Headers
  code += `headers = {\n`;
  if (apiConfig.authorization.type !== 'none') {
    if (apiConfig.authorization.type === 'bearer') {
      const tokenValue = apiConfig.authorization.headerValue || 'YOUR_TOKEN';
      code += `    'Authorization': 'Bearer ${tokenValue}',\n`;
    } else if (apiConfig.authorization.type === 'basic') {
      const username = apiConfig.authorization.username || 'YOUR_USERNAME';
      const password = apiConfig.authorization.password || 'YOUR_PASSWORD';
      code += `    'Authorization': 'Basic ' + base64.b64encode('${username}:${password}'.encode()).decode(),\n`;
    } else {
      const tokenValue = apiConfig.authorization.headerValue || 'YOUR_TOKEN';
      code += `    '${apiConfig.authorization.headerName}': '${tokenValue}',\n`;
    }
  }
  
  const validHeaders = getValidHeaders(tool.headers || []);
  if (validHeaders.length > 0) {
    validHeaders.forEach((header: any) => {
      code += `    '${header.name}': '${header.value}',\n`;
    });
  }
  code += `}\n\n`;
  
  // Body
  if ((tool.method === 'POST' || tool.method === 'PUT') && tool.bodyParams && tool.bodyParams.length > 0) {
    const bodyData = buildJsonBody(tool.bodyParams);
    code += `data = ${JSON.stringify(bodyData, null, 2)}\n\n`;
  }
  
  // Requete
  code += `response = requests.${tool.method.toLowerCase()}(`;
  if (tool.method === 'GET') {
    code += `url, headers=headers`;
  } else {
    code += `url, headers=headers, json=data`;
  }
  code += `)\n\n`;
  code += `print(f"Status Code: {response.status_code}")\n`;
  code += `print(response.json())`;
  
  return code;
};

export const generatePhpCode = (tool: any, apiConfig: any): string => {
  const fullUrl = getFullUrl(tool.endpoint, apiConfig.baseUrl);
  const urlParams = buildUrlParams(tool.parameters || []);
  const finalUrl = urlParams ? `${fullUrl}?${urlParams}` : fullUrl;
  
  let code = `<?php\n\n`;
  code += `$url = "${finalUrl}";\n\n`;
  
  // Headers
  code += `$headers = [\n`;
  if (apiConfig.authorization.type !== 'none') {
    if (apiConfig.authorization.type === 'bearer') {
      const tokenValue = apiConfig.authorization.headerValue || 'YOUR_TOKEN';
      code += `    'Authorization: Bearer ${tokenValue}',\n`;
    } else if (apiConfig.authorization.type === 'basic') {
      const username = apiConfig.authorization.username || 'YOUR_USERNAME';
      const password = apiConfig.authorization.password || 'YOUR_PASSWORD';
      code += `    'Authorization: Basic ' . base64_encode('${username}:${password}'),\n`;
    } else {
      const tokenValue = apiConfig.authorization.headerValue || 'YOUR_TOKEN';
      code += `    '${apiConfig.authorization.headerName}: ${tokenValue}',\n`;
    }
  }
  
  if (tool.headers && tool.headers.length > 0) {
    tool.headers.forEach((header: any) => {
      if (header.name && header.value) {
        code += `    '${header.name}: ${header.value}',\n`;
      }
    });
  }
  code += `];\n\n`;
  
  // Body
  if ((tool.method === 'POST' || tool.method === 'PUT') && tool.bodyParams && tool.bodyParams.length > 0) {
    const bodyData = buildJsonBody(tool.bodyParams);
    code += `$data = ${JSON.stringify(bodyData, null, 2)};\n\n`;
  }
  
  // Requete
  code += `$ch = curl_init();\n`;
  code += `curl_setopt($ch, CURLOPT_URL, $url);\n`;
  code += `curl_setopt($ch, CURLOPT_HTTPHEADER, $headers);\n`;
  code += `curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);\n`;
  code += `curl_setopt($ch, CURLOPT_CUSTOMREQUEST, '${tool.method}');\n`;
  
  if ((tool.method === 'POST' || tool.method === 'PUT') && tool.bodyParams && tool.bodyParams.length > 0) {
    code += `curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($data));\n`;
  }
  
  code += `\n$response = curl_exec($ch);\n`;
  code += `$httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);\n`;
  code += `curl_close($ch);\n\n`;
  code += `echo "Status Code: " . $httpCode . "\\n";\n`;
  code += `echo $response;`;
  
  return code;
};

export const generateNodeJsCode = (tool: any, apiConfig: any): string => {
  const fullUrl = getFullUrl(tool.endpoint, apiConfig.baseUrl);
  const urlParams = buildUrlParams(tool.parameters || []);
  const finalUrl = urlParams ? `${fullUrl}?${urlParams}` : fullUrl;
  
  let code = `const https = require('https');\n\n`;
  code += `const url = "${finalUrl}";\n\n`;
  
  // Headers
  code += `const headers = {\n`;
  if (apiConfig.authorization.type !== 'none') {
    if (apiConfig.authorization.type === 'bearer') {
      const tokenValue = apiConfig.authorization.headerValue || 'YOUR_TOKEN';
      // Remove "Bearer " prefix if it's already in the value
      const cleanToken = tokenValue.startsWith('Bearer ') ? tokenValue.substring(7) : tokenValue;
      code += `  'Authorization': 'Bearer ${cleanToken}',\n`;
    } else if (apiConfig.authorization.type === 'basic') {
      const username = apiConfig.authorization.username || 'YOUR_USERNAME';
      const password = apiConfig.authorization.password || 'YOUR_PASSWORD';
      code += `  'Authorization': 'Basic ' + Buffer.from('${username}:${password}').toString('base64'),\n`;
    } else {
      const tokenValue = apiConfig.authorization.headerValue || 'YOUR_TOKEN';
      code += `  '${apiConfig.authorization.headerName}': '${tokenValue}',\n`;
    }
  }
  
  if (tool.headers && tool.headers.length > 0) {
    tool.headers.forEach((header: any) => {
      if (header.name && header.value) {
        code += `  '${header.name}': '${header.value}',\n`;
      }
    });
  }
  code += `};\n\n`;
  
  // Body
  if ((tool.method === 'POST' || tool.method === 'PUT') && tool.bodyParams && tool.bodyParams.length > 0) {
    const bodyData = buildJsonBody(tool.bodyParams);
    code += `const data = ${JSON.stringify(bodyData, null, 2)};\n\n`;
  }
  
  // Requete
  code += `const options = {\n`;
  code += `  hostname: new URL(url).hostname,\n`;
  code += `  port: 443,\n`;
  code += `  path: new URL(url).pathname + new URL(url).search,\n`;
  code += `  method: '${tool.method}',\n`;
  code += `  headers: headers\n`;
  code += `};\n\n`;
  
  code += `const req = https.request(options, (res) => {\n`;
  code += `  console.log('Status Code:', res.statusCode);\n`;
  code += `  let data = '';\n`;
  code += `  res.on('data', (chunk) => {\n`;
  code += `    data += chunk;\n`;
  code += `  });\n`;
  code += `  res.on('end', () => {\n`;
  code += `    console.log(JSON.parse(data));\n`;
  code += `  });\n`;
  code += `});\n\n`;
  
  if ((tool.method === 'POST' || tool.method === 'PUT') && tool.bodyParams && tool.bodyParams.length > 0) {
    code += `req.write(JSON.stringify(data));\n`;
  }
  
  code += `req.end();`;
  
  return code;
};

export const generateGoCode = (tool: any, apiConfig: any): string => {
  const fullUrl = getFullUrl(tool.endpoint, apiConfig.baseUrl);
  const urlParams = buildUrlParams(tool.parameters || []);
  const finalUrl = urlParams ? `${fullUrl}?${urlParams}` : fullUrl;
  
  let code = `package main\n\nimport (\n`;
  code += `    "bytes"\n`;
  code += `    "encoding/base64"\n`;
  code += `    "encoding/json"\n`;
  code += `    "fmt"\n`;
  code += `    "io/ioutil"\n`;
  code += `    "net/http"\n`;
  code += `)\n\n`;
  
  // Body struct if needed
  if ((tool.method === 'POST' || tool.method === 'PUT') && tool.bodyParams && tool.bodyParams.length > 0) {
    code += `type RequestBody struct {\n`;
    tool.bodyParams.forEach((param: any) => {
      if (param.name) {
        code += `    ${param.name.charAt(0).toUpperCase() + param.name.slice(1)} string \`json:"${param.name}"\`\n`;
      }
    });
    code += `}\n\n`;
  }
  
  code += `func main() {\n`;
  code += `    url := "${finalUrl}"\n\n`;
  
  // Body
  if ((tool.method === 'POST' || tool.method === 'PUT') && tool.bodyParams && tool.bodyParams.length > 0) {
    const bodyData = buildJsonBody(tool.bodyParams);
    code += `    body := RequestBody{\n`;
    tool.bodyParams.forEach((param: any) => {
      if (param.name && param.value) {
        code += `        ${param.name.charAt(0).toUpperCase() + param.name.slice(1)}: "${param.value}",\n`;
      }
    });
    code += `    }\n`;
    code += `    jsonBody, _ := json.Marshal(body)\n\n`;
  }
  
  // Requete
  code += `    req, _ := http.NewRequest("${tool.method}", url, `;
  if ((tool.method === 'POST' || tool.method === 'PUT') && tool.bodyParams && tool.bodyParams.length > 0) {
    code += `bytes.NewBuffer(jsonBody)`;
  } else {
    code += `nil`;
  }
  code += `)\n\n`;
  
  // Headers
  if (apiConfig.authorization.type !== 'none') {
    if (apiConfig.authorization.type === 'bearer') {
      const tokenValue = apiConfig.authorization.headerValue || 'YOUR_TOKEN';
      code += `    req.Header.Set("Authorization", "Bearer ${tokenValue}")\n`;
    } else if (apiConfig.authorization.type === 'basic') {
      const username = apiConfig.authorization.username || 'YOUR_USERNAME';
      const password = apiConfig.authorization.password || 'YOUR_PASSWORD';
      code += `    req.Header.Set("Authorization", "Basic " + base64.StdEncoding.EncodeToString([]byte("${username}:${password}")))\n`;
    } else {
      const tokenValue = apiConfig.authorization.headerValue || 'YOUR_TOKEN';
      code += `    req.Header.Set("${apiConfig.authorization.headerName}", "${tokenValue}")\n`;
    }
  }
  
  if (tool.headers && tool.headers.length > 0) {
    tool.headers.forEach((header: any) => {
      if (header.name && header.value) {
        code += `    req.Header.Set("${header.name}", "${header.value}")\n`;
      }
    });
  }
  
  code += `\n    client := &http.Client{}\n`;
  code += `    resp, _ := client.Do(req)\n`;
  code += `    defer resp.Body.Close()\n\n`;
  code += `    bodyBytes, _ := ioutil.ReadAll(resp.Body)\n`;
  code += `    fmt.Printf("Status Code: %d\\n", resp.StatusCode)\n`;
  code += `    fmt.Println(string(bodyBytes))\n`;
  code += `}`;
  
  return code;
};

export const generateRubyCode = (tool: any, apiConfig: any): string => {
  const fullUrl = getFullUrl(tool.endpoint, apiConfig.baseUrl);
  const urlParams = buildUrlParams(tool.parameters || []);
  const finalUrl = urlParams ? `${fullUrl}?${urlParams}` : fullUrl;
  
  let code = `require 'net/http'
require 'json'
require 'uri'
require 'base64'

url = URI("${finalUrl}")

# Headers
headers = {
`;
  
  if (apiConfig.authorization.type !== 'none') {
    if (apiConfig.authorization.type === 'bearer') {
      const tokenValue = apiConfig.authorization.headerValue || 'YOUR_TOKEN';
      code += `  'Authorization' => 'Bearer ${tokenValue}',
`;
    } else if (apiConfig.authorization.type === 'basic') {
      const username = apiConfig.authorization.username || 'YOUR_USERNAME';
      const password = apiConfig.authorization.password || 'YOUR_PASSWORD';
      code += `  'Authorization' => 'Basic ' + Base64.strict_encode64('${username}:${password}'),
`;
    } else {
      const tokenValue = apiConfig.authorization.headerValue || 'YOUR_TOKEN';
      code += `  '${apiConfig.authorization.headerName}' => '${tokenValue}',
`;
    }
  }
  
  if (tool.headers && tool.headers.length > 0) {
    tool.headers.forEach((header: any) => {
      if (header.name && header.value) {
        code += `  '${header.name}' => '${header.value}',
`;
      }
    });
  }
  
  code += `}

# Body
`;
  
  if ((tool.method === 'POST' || tool.method === 'PUT') && tool.bodyParams && tool.bodyParams.length > 0) {
    const bodyData = buildJsonBody(tool.bodyParams);
    code += `data = ${JSON.stringify(bodyData, null, 2)}

`;
  }
  
  // Requete
  code += `# Requete
http = Net::HTTP.new(url.host, url.port)
http.use_ssl = true if url.scheme == 'https'

# Use the correct HTTP class based on the method
`;
  
  // Use the correct HTTP class based on the method
  const httpMethodClass = tool.method === 'GET' ? 'Get' : 
                          tool.method === 'POST' ? 'Post' : 
                          tool.method === 'PUT' ? 'Put' : 
                          tool.method === 'DELETE' ? 'Delete' : 'Get';
  
  code += `request = Net::HTTP::${httpMethodClass}.new(url)
request.body = data.to_json if defined?(data)

# Headers
headers.each { |key, value| request[key] = value }

response = http.request(request)
puts "Status Code: #{response.code}"
puts response.body`;
  
  return code;
};

export const generateJavaCode = (tool: any, apiConfig: any): string => {
  const fullUrl = getFullUrl(tool.endpoint, apiConfig.baseUrl);
  const urlParams = buildUrlParams(tool.parameters || []);
  const finalUrl = urlParams ? `${fullUrl}?${urlParams}` : fullUrl;
  
  let code = `import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.HashMap;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ApiClient {
    public static void main(String[] args) throws Exception {
        String url = "${finalUrl}";

        // Headers
        Map<String, String> headers = new HashMap<>();
`;
  
  if (apiConfig.authorization.type !== 'none') {
    if (apiConfig.authorization.type === 'bearer') {
      const tokenValue = apiConfig.authorization.headerValue || 'YOUR_TOKEN';
      code += `        headers.put("Authorization", "Bearer ${tokenValue}");
`;
    } else if (apiConfig.authorization.type === 'basic') {
      const username = apiConfig.authorization.username || 'YOUR_USERNAME';
      const password = apiConfig.authorization.password || 'YOUR_PASSWORD';
      code += `        String credentials = Base64.getEncoder().encodeToString(("${username}:${password}").getBytes());
        headers.put("Authorization", "Basic " + credentials);
`;
    } else {
      const tokenValue = apiConfig.authorization.headerValue || 'YOUR_TOKEN';
      code += `        headers.put("${apiConfig.authorization.headerName}", "${tokenValue}");
`;
    }
  }
  
  if (tool.headers && tool.headers.length > 0) {
    tool.headers.forEach((header: any) => {
      if (header.name && header.value) {
        code += `        headers.put("${header.name}", "${header.value}");
`;
      }
    });
  }
  
  code += `
        // Body
`;
  
  if ((tool.method === 'POST' || tool.method === 'PUT') && tool.bodyParams && tool.bodyParams.length > 0) {
    const bodyData = buildJsonBody(tool.bodyParams);
    code += `        String jsonBody = new ObjectMapper().writeValueAsString(Map.of(
`;
    Object.entries(bodyData).forEach(([key, value], index, array) => {
      code += `            "${key}", "${value}"${index < array.length - 1 ? ',' : ''}
`;
    });
    code += `        ));

`;
  }
  
  // Construction de la requete
  code += `        // Construction de la requete
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30));

        // Ajout des headers
        headers.forEach(requestBuilder::header);

        // Methode et body
`;
  
  if ((tool.method === 'POST' || tool.method === 'PUT') && tool.bodyParams && tool.bodyParams.length > 0) {
    code += `        HttpRequest request = requestBuilder
            .${tool.method.toLowerCase()}(HttpRequest.BodyPublishers.ofString(jsonBody))
            .header("Content-Type", "application/json")
            .build();
`;
  } else {
    code += `        HttpRequest request = requestBuilder
            .${tool.method.toLowerCase()}()
            .build();
`;
  }
  
  code += `
        // Execution de la requete
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println("Status Code: " + response.statusCode());
        System.out.println("Response Body: " + response.body());
    }
}`;
  
  return code;
};

export const generateShellCode = (tool: any, apiConfig: any): string => {
  const fullUrl = getFullUrl(tool.endpoint, apiConfig.baseUrl);
  const urlParams = buildUrlParams(tool.parameters || []);
  const finalUrl = urlParams ? `${fullUrl}?${urlParams}` : fullUrl;
  
  let code = `#!/bin/bash

URL="${finalUrl}"

# Headers
HEADERS=(
`;
  
  if (apiConfig.authorization.type !== 'none') {
    if (apiConfig.authorization.type === 'bearer') {
      const tokenValue = apiConfig.authorization.headerValue || 'YOUR_TOKEN';
      code += `  "Authorization: Bearer ${tokenValue}"
`;
    } else if (apiConfig.authorization.type === 'basic') {
      const username = apiConfig.authorization.username || 'YOUR_USERNAME';
      const password = apiConfig.authorization.password || 'YOUR_PASSWORD';
      code += `  "Authorization: Basic $(echo -n '${username}:${password}' | base64)"
`;
    } else {
      const tokenValue = apiConfig.authorization.headerValue || 'YOUR_TOKEN';
      code += `  "${apiConfig.authorization.headerName}: ${tokenValue}"
`;
    }
  }
  
  const validHeaders = getValidHeaders(tool.headers || []);
  if (validHeaders.length > 0) {
    validHeaders.forEach((header: any) => {
      code += `  "${header.name}: ${header.value}"
`;
    });
  }
  
  code += `)

# Body
`;
  
  if ((tool.method === 'POST' || tool.method === 'PUT') && tool.bodyParams && tool.bodyParams.length > 0) {
    const bodyData = buildJsonBody(tool.bodyParams);
    code += `BODY='${JSON.stringify(bodyData)}'

`;
  }
  
  // Construction de la commande curl
  code += `# Construction de la commande curl
CURL_CMD="curl -X ${tool.method} \\"$URL\\""

# Headers - utiliser une approche plus simple
for header in "${"$"}{HEADERS[@]}"; do
  CURL_CMD="$CURL_CMD -H \\"$header\\""
done

`;
  
  // Body
  if ((tool.method === 'POST' || tool.method === 'PUT') && tool.bodyParams && tool.bodyParams.length > 0) {
    code += `# Body
CURL_CMD="$CURL_CMD -d \\"$BODY\\""

`;
  }
  
  code += `echo "Executing: $CURL_CMD"
eval $CURL_CMD`;
  
  return code;
};

export const generateCodeExamples = (tool: any, apiConfig: any) => {
  return {
    curl: generateCurlCommand(tool, apiConfig),
    javascript: generateJavaScriptCode(tool, apiConfig),
    python: generatePythonCode(tool, apiConfig),
    java: generateJavaCode(tool, apiConfig),
    php: generatePhpCode(tool, apiConfig),
    nodejs: generateNodeJsCode(tool, apiConfig),
    go: generateGoCode(tool, apiConfig),
    ruby: generateRubyCode(tool, apiConfig),
    shell: generateShellCode(tool, apiConfig)
  };
};
