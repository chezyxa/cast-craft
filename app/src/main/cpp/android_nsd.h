/*
 * Cast Craft - bridge between the UxPlay dnssd API and Android's NsdManager.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef CASTCRAFT_ANDROID_NSD_H
#define CASTCRAFT_ANDROID_NSD_H

#ifdef __cplusplus
extern "C" {
#endif

/* Implemented in jni_bridge.c: forwards to the Java NsdManager wrapper. */
int android_nsd_register(const char *type, const char *name, unsigned short port,
                         const char **keys, const char **values, int count);
void android_nsd_unregister(const char *type);

#ifdef __cplusplus
}
#endif
#endif
