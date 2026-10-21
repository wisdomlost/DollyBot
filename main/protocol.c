#include "protocol.h"
#include <math.h>
#include <string.h>
#include "cJSON.h"

static bool number(const cJSON *item, double min, double max)
{
    return cJSON_IsNumber(item) && isfinite(item->valuedouble) &&
           item->valuedouble >= min && item->valuedouble <= max;
}

bool protocol_parse(const char *data, size_t length, command_t *out)
{
    if (!data || !out || !length || length > PROTOCOL_MAX_BYTES || memchr(data, '\0', length)) {
        return false;
    }
    // Protocol keys and values are ASCII tokens; reject escapes, including hidden NULs.
    if (memchr(data, '\\', length)) return false;
    const char *end = NULL;
    cJSON *root = cJSON_ParseWithLengthOpts(data, length, &end, false);
    if (!root) return false;
    bool ok = false;
    while (end < data + length && (*end == ' ' || *end == '\r' || *end == '\n' || *end == '\t')) ++end;
    if (!cJSON_IsObject(root) || end != data + length) goto done;
    const cJSON *v = cJSON_GetObjectItemCaseSensitive(root, "v");
    const cJSON *seq = cJSON_GetObjectItemCaseSensitive(root, "seq");
    const cJSON *type = cJSON_GetObjectItemCaseSensitive(root, "type");
    if (!number(v, 1, 1) || !number(seq, 1, UINT32_MAX) || floor(seq->valuedouble) != seq->valuedouble ||
        !cJSON_IsString(type)) goto done;
    command_t cmd = {.seq = (uint32_t)seq->valuedouble};
    if (!strcmp(type->valuestring, "drive")) {
        cmd.type = CMD_DRIVE;
        const cJSON *linear = cJSON_GetObjectItemCaseSensitive(root, "linear");
        const cJSON *angular = cJSON_GetObjectItemCaseSensitive(root, "angular");
        if (!number(linear, -1, 1) || !number(angular, -1, 1)) goto done;
        cmd.linear = (float)linear->valuedouble;
        cmd.angular = (float)angular->valuedouble;
    } else if (!strcmp(type->valuestring, "stop")) {
        cmd.type = CMD_STOP;
    } else if (!strcmp(type->valuestring, "ping")) {
        cmd.type = CMD_PING;
    } else goto done;
    // Reject unknown and duplicate keys rather than accepting ambiguous commands.
    unsigned seen = 0;
    const cJSON *item;
    cJSON_ArrayForEach(item, root) {
        unsigned bit = 0;
        if (!strcmp(item->string, "v")) bit = 1;
        else if (!strcmp(item->string, "seq")) bit = 2;
        else if (!strcmp(item->string, "type")) bit = 4;
        else if (cmd.type == CMD_DRIVE && !strcmp(item->string, "linear")) bit = 8;
        else if (cmd.type == CMD_DRIVE && !strcmp(item->string, "angular")) bit = 16;
        if (!bit || (seen & bit)) goto done;
        seen |= bit;
    }
    *out = cmd;
    ok = true;
done:
    cJSON_Delete(root);
    return ok;
}
