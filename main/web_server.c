#include "web_server.h"
#include "control.h"
#include "motion_link.h"
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include "esp_http_server.h"
#include "esp_system.h"
#include "esp_timer.h"

extern const unsigned char html_start[] asm("_binary_index_html_start");
extern const unsigned char html_end[] asm("_binary_index_html_end");
extern const unsigned char css_start[] asm("_binary_style_css_start");
extern const unsigned char css_end[] asm("_binary_style_css_end");
extern const unsigned char js_start[] asm("_binary_app_js_start");
extern const unsigned char js_end[] asm("_binary_app_js_end");

static esp_err_t asset(httpd_req_t *req)
{
    const unsigned char *start = html_start, *end = html_end;
    const char *type = "text/html; charset=utf-8";
    if (!strcmp(req->uri, "/style.css")) {
        start = css_start; end = css_end; type = "text/css";
    } else if (!strcmp(req->uri, "/app.js")) {
        start = js_start; end = js_end; type = "text/javascript";
    }
    httpd_resp_set_type(req, type);
    httpd_resp_set_hdr(req, "Cache-Control", "no-store");
    httpd_resp_set_hdr(req, "X-Content-Type-Options", "nosniff");
    httpd_resp_set_hdr(req, "Content-Security-Policy", "default-src 'self'; connect-src 'self' ws://192.168.4.1; style-src 'self'; script-src 'self'; frame-ancestors 'none'");
    return httpd_resp_send(req, (const char *)start, end - start);
}

static void closed(httpd_handle_t server, int fd)
{
    (void)server;
    control_disconnect(fd);
    close(fd); // A custom close callback is responsible for closing the socket.
}

static esp_err_t websocket(httpd_req_t *req)
{
    int fd = httpd_req_to_sockfd(req);
    if (req->method == HTTP_GET) {
        char origin[80];
        if (httpd_req_get_hdr_value_str(req, "Origin", origin, sizeof(origin)) != ESP_OK ||
            strcmp(origin, "http://192.168.4.1")) {
            httpd_resp_send_err(req, HTTPD_403_FORBIDDEN, "origin denied");
            return ESP_FAIL;
        }
        if (!control_connect(fd)) {
            httpd_resp_set_status(req, "409 Conflict");
            httpd_resp_sendstr(req, "controller busy");
            return ESP_FAIL;
        }
        return ESP_OK;
    }
    httpd_ws_frame_t frame = {0};
    esp_err_t err = httpd_ws_recv_frame(req, &frame, 0);
    if (err != ESP_OK) { control_reject(fd); return err; }
    if (frame.type != HTTPD_WS_TYPE_TEXT || !frame.final || frame.len > PROTOCOL_MAX_BYTES || !frame.len) {
        control_reject(fd);
        return ESP_FAIL;
    }
    char payload[PROTOCOL_MAX_BYTES + 1] = {0};
    frame.payload = (uint8_t *)payload;
    err = httpd_ws_recv_frame(req, &frame, PROTOCOL_MAX_BYTES);
    command_t cmd;
    if (err != ESP_OK || !protocol_parse(payload, frame.len, &cmd) || !control_apply(fd, &cmd)) {
        control_reject(fd);
        return ESP_FAIL;
    }
    control_state_t state;
    control_snapshot(&state);
    char response[420];
    int len = snprintf(response, sizeof(response),
        "{\"v\":1,\"type\":\"state\",\"ack\":%lu,\"simulated\":%s,"
        "\"stm32_connected\":false,\"linear\":%.3f,\"angular\":%.3f,\"reason\":\"%s\","
        "\"uptime_ms\":%lld,\"free_heap\":%lu,\"timeout_ms\":%d}",
        (unsigned long)state.last_seq, motion_link_is_simulated() ? "true" : "false",
        (double)state.linear, (double)state.angular, state.reason,
        (long long)(esp_timer_get_time() / 1000), (unsigned long)esp_get_free_heap_size(), CONTROL_TIMEOUT_MS);
    if (len < 0 || len >= sizeof(response)) return ESP_FAIL;
    httpd_ws_frame_t reply = {.type = HTTPD_WS_TYPE_TEXT, .payload = (uint8_t *)response, .len = (size_t)len};
    err = httpd_ws_send_frame(req, &reply);
    if (err != ESP_OK) control_reject(fd);
    return err;
}

void web_server_start(void)
{
    httpd_config_t config = HTTPD_DEFAULT_CONFIG();
    config.max_open_sockets = 5;
    config.stack_size = 6144;
    config.recv_wait_timeout = 1;
    config.send_wait_timeout = 1;
    config.close_fn = closed;
    httpd_handle_t server = NULL;
    ESP_ERROR_CHECK(httpd_start(&server, &config));
    const httpd_uri_t routes[] = {
        {.uri = "/", .method = HTTP_GET, .handler = asset},
        {.uri = "/style.css", .method = HTTP_GET, .handler = asset},
        {.uri = "/app.js", .method = HTTP_GET, .handler = asset},
        {.uri = "/ws", .method = HTTP_GET, .handler = websocket, .is_websocket = true},
    };
    for (size_t i = 0; i < sizeof(routes) / sizeof(routes[0]); ++i) {
        ESP_ERROR_CHECK(httpd_register_uri_handler(server, &routes[i]));
    }
}
