/*
 * Cast Craft - dnssd backend for Android.
 *
 * Replaces UxPlay's Avahi/Bonjour (lib/dns_sd) and built-in mDNS responder
 * (lib/mdnsd) backends. The TXT records are the same ones UxPlay advertises
 * (see lib/mdnsd/dnssd_mdnsd.c); the actual registration is done by Android's
 * system mDNS daemon through NsdManager on the Java side.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "uxplay/lib/dnssd.h"
#include "uxplay/lib/dnssdint.h"
#include "uxplay/lib/global.h"
#include "uxplay/lib/utils.h"

#include "android_nsd.h"

#define MAX_TXT_ITEMS 32
#define MAX_TXT_BYTES 1024
#define MAX_NAME 256

typedef struct {
    char *keys[MAX_TXT_ITEMS];
    char *values[MAX_TXT_ITEMS];
    int count;
    unsigned char bytes[MAX_TXT_BYTES];
    int length;
} txt_record_t;

typedef struct dnssd_private_s {
    txt_record_t raop;
    txt_record_t airplay;
    char raop_name[MAX_NAME];
    char airplay_name[MAX_NAME];
    int raop_registered;
    int airplay_registered;
} dnssd_private_t;

static void txt_clear(txt_record_t *txt)
{
    for (int i = 0; i < txt->count; i++) {
        free(txt->keys[i]);
        free(txt->values[i]);
        txt->keys[i] = NULL;
        txt->values[i] = NULL;
    }
    txt->count = 0;
    txt->length = 0;
}

/* returns non-zero on failure, like mdnsd_txt_add */
static int txt_add(txt_record_t *txt, const char *key, const char *value)
{
    if (!value) value = "";
    size_t klen = strlen(key);
    size_t vlen = strlen(value);
    size_t item = klen + 1 + vlen;
    if (txt->count >= MAX_TXT_ITEMS || item > 255 || txt->length + 1 + (int) item > MAX_TXT_BYTES) {
        return -1;
    }
    txt->keys[txt->count] = strdup(key);
    txt->values[txt->count] = strdup(value);
    txt->count++;

    unsigned char *p = txt->bytes + txt->length;
    *p++ = (unsigned char) item;
    memcpy(p, key, klen);
    p += klen;
    *p++ = '=';
    memcpy(p, value, vlen);
    txt->length += 1 + (int) item;
    return 0;
}

void *dnssd_private_init(dnssd_t *dnssd_public, int *error)
{
    char hwaddr[2 * MAX_HWADDR_LEN + 1];
    if (error) *error = DNSSD_ERROR_NOERROR;

    dnssd_private_t *dnssd = (dnssd_private_t *) calloc(1, sizeof(dnssd_private_t));
    if (!dnssd) {
        if (error) *error = DNSSD_ERROR_OUTOFMEM;
        return NULL;
    }
    if (utils_hwaddr_raop(hwaddr, sizeof(hwaddr), dnssd_public->hw_addr, dnssd_public->hw_addr_len) < 0) {
        free(dnssd);
        if (error) *error = DNSSD_ERROR_HWADDRLEN;
        return NULL;
    }
    /* NsdManager takes the instance name only; type and domain are separate */
    snprintf(dnssd->raop_name, sizeof(dnssd->raop_name), "%s@%s", hwaddr, dnssd_public->name);
    snprintf(dnssd->airplay_name, sizeof(dnssd->airplay_name), "%s", dnssd_public->name);
    return dnssd;
}

void dnssd_private_destroy(void *private)
{
    dnssd_private_t *dnssd = (dnssd_private_t *) private;
    if (!dnssd) return;
    txt_clear(&dnssd->raop);
    txt_clear(&dnssd->airplay);
    free(dnssd);
}

static int build_raop_txt(dnssd_t *pub, txt_record_t *txt)
{
    char features[22] = {0};
    const char *pw = "false";
    const char *sf = RAOP_SF;
    switch (pub->pin_pw) {
    case 1:
        pw = "true";
        sf = "0x8c";
        break;
    case 2:
    case 3:
        pw = "true";
        sf = "0x84";
        break;
    default:
        break;
    }
    snprintf(features, sizeof(features), "0x%X,0x%X", pub->features1, pub->features2);
    txt_clear(txt);
    return txt_add(txt, "ch", RAOP_CH) ||
           txt_add(txt, "cn", RAOP_CN) ||
           txt_add(txt, "da", RAOP_DA) ||
           txt_add(txt, "et", RAOP_ET) ||
           txt_add(txt, "vv", RAOP_VV) ||
           txt_add(txt, "ft", features) ||
           txt_add(txt, "am", GLOBAL_MODEL) ||
           txt_add(txt, "md", RAOP_MD) ||
           txt_add(txt, "rhd", RAOP_RHD) ||
           txt_add(txt, "pw", pw) ||
           txt_add(txt, "sr", RAOP_SR) ||
           txt_add(txt, "ss", RAOP_SS) ||
           txt_add(txt, "sv", RAOP_SV) ||
           txt_add(txt, "tp", RAOP_TP) ||
           txt_add(txt, "txtvers", RAOP_TXTVERS) ||
           txt_add(txt, "sf", sf) ||
           txt_add(txt, "vs", RAOP_VS) ||
           txt_add(txt, "vn", RAOP_VN) ||
           txt_add(txt, "pk", pub->pk);
}

static int build_airplay_txt(dnssd_t *pub, txt_record_t *txt)
{
    char device_id[3 * MAX_HWADDR_LEN];
    char features[22] = {0};
    if (utils_hwaddr_airplay(device_id, sizeof(device_id), pub->hw_addr, pub->hw_addr_len) < 0) {
        return -1;
    }
    snprintf(features, sizeof(features), "0x%X,0x%X", pub->features1, pub->features2);
    txt_clear(txt);
    return txt_add(txt, "deviceid", device_id) ||
           txt_add(txt, "features", features) ||
           txt_add(txt, "pw", pub->pin_pw ? "true" : "false") ||
           txt_add(txt, "flags", "0x4") ||
           txt_add(txt, "model", GLOBAL_MODEL) ||
           txt_add(txt, "pk", pub->pk) ||
           txt_add(txt, "pi", AIRPLAY_PI) ||
           txt_add(txt, "srcvers", AIRPLAY_SRCVERS) ||
           txt_add(txt, "vv", AIRPLAY_VV);
}

int dnssd_register_raop(dnssd_t *pub, unsigned short port)
{
    assert(pub && pub->dnssd_private);
    dnssd_private_t *dnssd = (dnssd_private_t *) pub->dnssd_private;
    if (build_raop_txt(pub, &dnssd->raop)) return -1;
    int ret = android_nsd_register("_raop._tcp", dnssd->raop_name, port,
                                   (const char **) dnssd->raop.keys,
                                   (const char **) dnssd->raop.values, dnssd->raop.count);
    if (!ret) dnssd->raop_registered = 1;
    return ret;
}

int dnssd_register_airplay(dnssd_t *pub, unsigned short port)
{
    assert(pub && pub->dnssd_private);
    dnssd_private_t *dnssd = (dnssd_private_t *) pub->dnssd_private;
    if (build_airplay_txt(pub, &dnssd->airplay)) return -1;
    int ret = android_nsd_register("_airplay._tcp", dnssd->airplay_name, port,
                                   (const char **) dnssd->airplay.keys,
                                   (const char **) dnssd->airplay.values, dnssd->airplay.count);
    if (!ret) dnssd->airplay_registered = 1;
    return ret;
}

void dnssd_unregister_raop(dnssd_t *pub)
{
    assert(pub && pub->dnssd_private);
    dnssd_private_t *dnssd = (dnssd_private_t *) pub->dnssd_private;
    if (dnssd->raop_registered) {
        android_nsd_unregister("_raop._tcp");
        dnssd->raop_registered = 0;
    }
}

void dnssd_unregister_airplay(dnssd_t *pub)
{
    assert(pub && pub->dnssd_private);
    dnssd_private_t *dnssd = (dnssd_private_t *) pub->dnssd_private;
    if (dnssd->airplay_registered) {
        android_nsd_unregister("_airplay._tcp");
        dnssd->airplay_registered = 0;
    }
}

const char *dnssd_get_raop_txt(dnssd_t *pub, int *length)
{
    dnssd_private_t *dnssd = (dnssd_private_t *) pub->dnssd_private;
    *length = dnssd->raop.length;
    return (const char *) dnssd->raop.bytes;
}

const char *dnssd_get_airplay_txt(dnssd_t *pub, int *length)
{
    dnssd_private_t *dnssd = (dnssd_private_t *) pub->dnssd_private;
    *length = dnssd->airplay.length;
    return (const char *) dnssd->airplay.bytes;
}

int dnssd_get_service_fd(dnssd_t *dnssd, int service)
{
    (void) dnssd;
    (void) service;
    return -1;
}

int dnssd_process_service(dnssd_t *dnssd, int service)
{
    (void) dnssd;
    (void) service;
    return 0;
}

void dnssd_error_text(int *error, const char *appname)
{
    (void) appname;
    fprintf(stderr, "dnssd (Android NsdManager) error %d\n", error ? *error : 0);
}
