cmake_minimum_required(VERSION 3.8)
project(rplidar_bringup)

find_package(ament_cmake REQUIRED)

install(DIRECTORY launch config
  DESTINATION share/${PROJECT_NAME})

install(DIRECTORY udev
  DESTINATION share/${PROJECT_NAME})

ament_package()
