#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define ALLOC_SIZE 1024 * 1024 * 100 // 100 MB
#define NUM_ALLOCS 1000              // Number of allocations

int main() {
    char **memory_blocks = malloc(NUM_ALLOCS * sizeof(char *));
    if (memory_blocks == NULL) {
        perror("malloc");
        return 1;
    }

    printf("Starting memory allocations...\n");

    for (int i = 0; i < NUM_ALLOCS; i++) {
        // 각 할당은 100MB
        memory_blocks[i] = malloc(ALLOC_SIZE);
        if (memory_blocks[i] == NULL) {
            perror("malloc");
            break;
        }
        
        memset(memory_blocks[i], 0, ALLOC_SIZE);

        printf("Allocated %d MB\n", (i + 1) * 100);
        
        sleep(1);
    }

    for (int i = 0; i < NUM_ALLOCS; i++) {
        if (memory_blocks[i] != NULL) {
            free(memory_blocks[i]);
        }
    }

    free(memory_blocks);
    printf("Memory has been freed.\n");
    return 0;
}
