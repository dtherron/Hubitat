# Mitsubishi2Mqtt Thermostat for Hubitat

Mitsubishi2Mqtt Thermostat is a Hubitat Device Type Handler that lets you connect to a Mitsubish heat pump via MQTT. The heat pump must be communicating via MQTT using the code from https://github.com/gysmo38/mitsubishi2MQTT.

This project was forked directly from https://github.com/NelsonClark so that I didn't have to figure out how to implement a thermostat (with the goal of being able to have Google Home see the Hubitat thermostat.) 

This device handler allows the creation of a new Device thats shows as a thermostat, and the configuration of the MQTT broker to communicate with it.

# Installation

* Use Hubitat Package Manager to import the package, or

* Import the Parent and Child apps (in that order) in the <> Apps Code section of your Hubitat Hub
* Import the Device driver in the <> Drivers Code section of your Hubitat Hub
* Go to the Apps Section and use the + Add User App button in the top right corner to add the Mitsubishi2Mqtt Thermostat Manager, then click DONE
* Go back in the Mitsubishi2Mqtt Thermostat Manager, create a new Mitsubishi2Mqtt Thermostat and select your devices and settings, then click DONE
* A child app is created so you can change the devices used at any time for that Mitsubishi2Mqtt Thermostat, a new device is also created where you can set it up like any other thermostat

Enjoy!

# Version History

* 0.1 - Original fork from NelsonClark

# ToDo

Things to do in upcomming releases...

- [ ] Allow additional (remote) sensors to be configured to improve the heating/cooling control
