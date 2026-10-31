/*
 * App work queue: a preemptible, low-priority thread for work that may block on
 * flash (button actions, battery checks). Unlike the system work queue it never
 * starves the audio thread.
 */

#ifndef OWL_WORKQ_H_
#define OWL_WORKQ_H_

#include <zephyr/kernel.h>

extern struct k_work_q app_workq;

void app_workq_start(void);

#endif /* OWL_WORKQ_H_ */
