#!/bin/bash
#
# SPDX-FileCopyrightText: 2020 Anuradha Weeraman <anuradha@weeraman.com>
# SPDX-License-Identifier: GPL-3.0-or-later

set -e

kernel=$1

basedir=$(dirname $(readlink -f $0))
. ${basedir}/config/env.sh


qemu=qemu-system-${qemu_arch}

pidfile=vm.pid

check_availability_of() {
	prog=$1
	if ! command -v ${prog} 2>&1 > /dev/null; then
		echo "$prog not found in path. Please install and try again."
		exit 1
	fi
}

check_availability_of make
check_availability_of sudo
check_availability_of $qemu


if test ! -d ${srcdir}; then
  echo "Please copy kernel sources into ${srcdir}."
  exit 1
fi

count=$(ls -1 ${srcdir} | wc -l)
if test ${count} -eq 0; then
  echo "Please copy kernel sources into ${srcdir}."
  exit 1
fi

if test -z "${kernel}"; then
  echo "Please specify the kernel that you would like to use, such as:"
  for dir in $(find ${srcdir} -mindepth 1 -maxdepth 1 \
	                      -type d,l -exec basename {} \; | grep -v ${srcdir}); do
    [ -e ${srcdir}/${dir}/MAINTAINERS ] && echo $dir
  done
  exit 1
fi

if test -e ${pidfile}; then
  pid=$(sudo cat ${pidfile})
  echo "Please shutdown the running qemu process. PID:${pid}"
  echo "Execute ./exit_vm.sh"
  exit 1
fi

if test ! -e "${srcdir}/${kernel}"; then
  echo "Please copy kernel sources into src/. For example, src/stable"
  exit 1
fi

bzImage=${srcdir}/${kernel}/arch/${kernel_arch}/boot/bzImage
vmlinux=${srcdir}/${kernel}/vmlinux

if test ! -e ${bzImage}; then
  echo -n "No built kernel found, build one? (y / default: n) "
  read input
  if test "${input}y" = "yy"; then
    echo -n "Kernel configuration (default: config/default.cfg): "
    read kernelcfg
    if [ -z "${kernelcfg}" ]; then
      kernelcfg="${basedir}/config/default.cfg"
    fi
    echo "Building with ${kernelcfg}..."
    (
      cp ${kernelcfg} ${srcdir}/${kernel}/.config
      cd ${srcdir}/${kernel}
      time make ${compiler_flags} -j${procs} bzImage modules
    )
  else
    echo "Build the kernel manually, and try again."
    exit 0
  fi
fi

echo "Mounting ${rootfs} on loopback... "
if [ ! -e ${rootfs} ]; then
  sudo mkdir ${rootfs}
fi
sudo mount -o loop ${rootimg} ${rootfs}

if test -e ${vmlinux}; then
  sudo cp ${vmlinux} ${rootfs}/
fi

echo "Removing existing kernel modules..."
sudo rm -rf ${rootfs}/lib/modules/*


if test "${copy_modules_to_rootfs}x" = "yx"; then
    (
      cd ${srcdir}/${kernel}
      echo "Copying kernel modules to rootfs..."
      sudo make INSTALL_MOD_PATH=${rootfs} modules_install -j$(($(nproc) + 8))
    )
fi

sleep 4

if test "${copy_samples_to_rootfs}x" = "yx"; then
    (
      cd ${samplesdir}
      KERNEL_PATH=${srcdir}/${kernel} PROCS=${procs} make ${compiler_flags} all

      echo "Copying sample kernel modules to rootfs..."
      sudo KERNEL_PATH=${srcdir}/${kernel} INSTALL_MOD_PATH=${rootfs} make install
    )
fi

if test "${copy_labs_to_rootfs}x" = "yx"; then
    (
      labs=$(ls ${labdir})
      echo "Removing existing lab sources from rootfs..."
      sudo rm -rf ${rootfs}/root/labs  # Remove existing labs folder

      echo "Copying lab sources to rootfs..."
      sudo mkdir -p ${rootfs}/root/labs  # Create new labs folder
      sudo cp -R ${labdir}/* ${rootfs}/root/labs/  # Recursively copy all contents
    )
fi

if test "${copy_proj_to_rootfs}x" = "yx"; then
    (
      labs=$(ls ${projdir})

      echo "Copying project sources to rootfs..."
      find ${projdir} -type f | xargs --no-run-if-empty sudo cp --parents -t ${rootfs}/root || true
    )
fi

sync
sudo umount ${rootfs}
sudo rmdir ${rootfs}


echo "Booting kernel: ${bzImage}"

debug_args=""
if test "${wait_for_gdb_at_boot}y" = "yy"; then
  echo "-----------------------------------------------------------"
  echo "Debug mode Enabled. Attach to vmlinux in your GDB console."
  echo "-----------------------------------------------------------"
  debug_args=${qemu_debug_args}
fi

append_args=""
root_device=""
initrd_args=""
if test "${boot_into_initrd_shell}y" = "yy"; then
  append_args="rdinit=/init"
  initrd_args="-initrd ${basedir}/initramfs.cpio.gz"
else
  root_device="root=/dev/sda"
  append_args="init=/lib/systemd/systemd"
fi

# ${qemu} -m ${memory} -kernel ${bzImage} ${initrd_args} -nographic \
# 	-hda ${basedir}/${rootimg} \
# 	-append "${root_device} rw console=ttyS0 earlyprintk=vga nokaslr selinux=0 debug ${append_args}" \
# 	-pidfile ${pidfile} 2>&1 | tee vm.log
# 	# -enable-kvm ${debug_args}


sudo ${qemu} \
	-enable-kvm \
	-nographic \
	-cpu host \
	-smp 4 \
	-m 8G \
	-kernel ${bzImage} \
	-object memory-backend-ram,size=4G,id=m0 \
	-object memory-backend-ram,size=4G,id=m1 \
	-numa node,nodeid=0,memdev=m0,cpus=0-1 \
	-numa node,nodeid=1,memdev=m1,cpus=2-3 \
	-append "rw console=ttyS0,115200 root=/dev/sda nokaslr" \
	-drive file=${rootimg},format=raw \
	-pidfile ${pidfile} \
	${debug_args} \
	2>&1 | tee vm.log
