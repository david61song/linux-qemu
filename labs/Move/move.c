#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <numa.h>
#include <numaif.h>
#include <sys/mman.h>
#include <stdint.h>
#include <time.h> // time()

#define BUFFER_SIZE (4096 * 10)  // 10 pages buffer
#define BASE_NODEMASK (1UL)

				       
void *get_buffer(uint64_t size, int is_hugepage, int numanode_num)
{
	void *addr = NULL;
	int prot = PROT_READ | PROT_WRITE;
	int flags = MAP_PRIVATE | MAP_ANONYMOUS;
	int fd = -1;
	off_t offset = 0;
	long res;
	unsigned long nodemask = (BASE_NODEMASK << (unsigned long)numanode_num);

	// Just allocate buffer, don't need to touch it.
	addr = mmap(NULL, size, prot, flags, fd, offset);

	res = mbind(addr, size, MPOL_BIND, &nodemask, 64, MPOL_MF_STRICT);
	if (res != 0) {
		perror("mbind");
		exit(EXIT_FAILURE);
	}

	if (addr == NULL || addr == MAP_FAILED) {
		perror("mmap");
		exit(EXIT_FAILURE);
	}

	return addr;
}


void *get_random_buffer(uint64_t size, int is_hugepage, int numanode_num)
{
	void *addr = get_buffer(size, is_hugepage, numanode_num);
	// Initialize random seed (combining current time and process ID)
	srand(time(NULL));
	// Fill addr buffer with random data (byte unit)
	unsigned char *buffer = (unsigned char *)addr;
	for (uint64_t i = 0; i < size; i++) {
		buffer[i] = (unsigned char)(rand() & 0xFF);
	}

	return addr;
}


void migrate_buffer(void *addr, uint64_t size, int numanode_num)
{
	unsigned long nodemask = (BASE_NODEMASK << (unsigned long)numanode_num);
	long res;

	// If kernel failed to migrate any page, it will return -1 (MPOL_MF_STRICT option is set)
	res = mbind(addr, size, MPOL_BIND, &nodemask, 64,
		    MPOL_MF_MOVE | MPOL_MF_STRICT);
	if (res != 0) {
		perror("mbind");
		exit(EXIT_FAILURE);
	}
}

int main() {
    // Check if NUMA is available
    if (numa_available() == -1) {
        fprintf(stderr, "NUMA is not available on this system.\n");
        return EXIT_FAILURE;
    }

    // Allocate a buffer using mmap with anonymous memory (Node 0)
    void *buffer = get_random_buffer(BUFFER_SIZE, 0, 0);
    if (!buffer) {
        perror("mmap failed");
        return EXIT_FAILURE;
    }

    /* Zero'ing buffer */
    memset(buffer, 0, BUFFER_SIZE);


    // Migrate
    migrate_buffer(buffer, BUFFER_SIZE, 1);

    printf("Memory successfully moved to NUMA node 1.\n");

    // Free the allocated memory
    if (munmap(buffer, BUFFER_SIZE) != 0) {
        perror("munmap failed");
        return EXIT_FAILURE;
    }

    printf("Memory unmapped successfully.\n");
    return EXIT_SUCCESS;
}
