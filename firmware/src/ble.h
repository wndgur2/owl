/*
 * BLE peripheral: Owl GATT service (protocol §2), advertising, and the
 * CONTROL / DATA handling (LIST, FETCH streaming with flow control, ACK).
 */

#ifndef OWL_BLE_H_
#define OWL_BLE_H_

#include <stdbool.h>

int ble_init(void);
/* Push a STATUS notification if a phone is subscribed (cheap to call often). */
void ble_notify_status(void);
bool ble_is_connected(void);
/* Disconnect and stop advertising (before System OFF). */
void ble_stop(void);

#endif /* OWL_BLE_H_ */
