#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "control.h"
#include "motion_link.h"
static int64_t now;
static float output_linear, output_angular;
int64_t esp_timer_get_time(void) { return now; }
void motion_link_init(void) {}
void motion_link_set(float linear, float angular) { output_linear = linear; output_angular = angular; }
bool motion_link_is_simulated(void) { return true; }
static void parse(const char *json, bool valid)
{
    command_t cmd;
    assert(protocol_parse(json, strlen(json), &cmd) == valid);
}
int main(void)
{
    parse("{\"v\":1,\"seq\":1,\"type\":\"drive\",\"linear\":-1,\"angular\":1}", true);
    parse("{\"v\":1,\"seq\":4294967295,\"type\":\"stop\"} \n", true);
    const char *bad[] = {"", "[]", "null", "{", "{}", "{\"v\":2,\"seq\":1,\"type\":\"stop\"}",
        "{\"v\":1,\"seq\":0,\"type\":\"stop\"}", "{\"v\":1,\"seq\":1.5,\"type\":\"stop\"}",
        "{\"v\":1,\"seq\":4294967296,\"type\":\"stop\"}", "{\"v\":1,\"seq\":1,\"type\":\"record\"}",
        "{\"v\":1,\"seq\":1,\"type\":\"drive\",\"linear\":1.1,\"angular\":0}",
        "{\"v\":1,\"seq\":1,\"type\":\"drive\",\"linear\":0}",
        "{\"v\":1,\"seq\":1,\"type\":\"stop\",\"seq\":2}",
        "{\"v\":1,\"seq\":1,\"type\":\"stop\",\"extra\":0}",
        "{\"v\":1,\"seq\":1,\"type\":\"stop\"}{}",
        "{\"v\":1,\"seq\":1,\"type\":\"stop\\u0000drive\"}",
        "{\"v\":1,\"seq\":1,\"type\":\"drive\",\"linear\":1e999,\"angular\":0}",
        "{\"v\":true,\"seq\":1,\"type\":\"stop\"}"};
    for (size_t i = 0; i < sizeof(bad)/sizeof(bad[0]); ++i) parse(bad[i], false);
    command_t cmd;
    char oversized[257]; memset(oversized, ' ', sizeof(oversized));
    assert(!protocol_parse(oversized, sizeof(oversized), &cmd));
    const char nul[] = "{\"v\":1,\"seq\":1,\"type\":\"stop\"}\0x";
    assert(!protocol_parse(nul, sizeof(nul)-1, &cmd));
    control_init(); assert(control_connect(5)); assert(!control_connect(6));
    cmd = (command_t){.type=CMD_DRIVE,.seq=1,.linear=.5f,.angular=-.2f};
    assert(control_apply(5,&cmd)); assert(output_linear == .5f);
    control_disconnect(6); assert(output_linear == .5f);
    now = 499000; control_tick(); assert(output_linear == .5f);
    command_t ping = {.type=CMD_PING,.seq=2}; assert(control_apply(5,&ping));
    now = 500000; control_tick(); assert(output_linear == 0 && output_angular == 0);
    control_state_t s; control_snapshot(&s); assert(!strcmp(s.reason,"timeout"));
    cmd.seq=3; assert(control_apply(5,&cmd));
    assert(!control_apply(5,&cmd)); assert(output_linear == 0);
    cmd.seq=4; assert(control_apply(5,&cmd)); control_reject(5); assert(output_linear == 0);
    cmd.seq=5; assert(control_apply(5,&cmd)); control_disconnect(5); assert(output_linear == 0);
    assert(!control_apply(5,&cmd)); assert(control_connect(7));
    cmd.seq=1; assert(control_apply(7,&cmd)); cmd.seq=2;cmd.type=CMD_STOP;
    assert(control_apply(7,&cmd)); assert(output_linear == 0);
    puts("PASS: parser boundary cases; ownership, sequence, stop, disconnect, invalid message, 500ms timeout, ping isolation");
}
