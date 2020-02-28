/*
 * Copyright 2020 Aleksander Jagiełło <themolkapl@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package pl.craftserve.radiation;

import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.MemoryConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class BaseConfig {
    private static final char COLOR_CODE = '&';

    public static String colorize(String input) {
        return input == null ? null : ChatColor.translateAlternateColorCodes(COLOR_CODE, input);
    }

    private BaseConfig() {
    }

    public static class BarConfig {
        private final String title;
        private final BarColor color;
        private final BarStyle style;
        private final BarFlag[] flags;

        public BarConfig(String title, BarColor color, BarStyle style, BarFlag[] flags) {
            this.title = Objects.requireNonNull(title, "title");
            this.color = Objects.requireNonNull(color, "color");
            this.style = Objects.requireNonNull(style, "style");
            this.flags = Objects.requireNonNull(flags, "flags");
        }

        public BarConfig(ConfigurationSection section) throws InvalidConfigurationException {
            if (section == null) {
                section = new MemoryConfiguration();
            }

            this.title = colorize(section.getString("title"));

            String color = section.getString("color", BarColor.WHITE.name());
            if (color == null) {
                throw new InvalidConfigurationException("Missing bar color.");
            }

            try {
                this.color = BarColor.valueOf(color.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new InvalidConfigurationException("Unknown bar color: " + color);
            }

            String style = section.getString("style", BarStyle.SOLID.name());
            if (style == null) {
                throw new InvalidConfigurationException("Missing bar style.");
            }

            try {
                this.style = BarStyle.valueOf(style.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new InvalidConfigurationException("Unknown bar style: " + style);
            }

            List<BarFlag> flags = new ArrayList<>();
            for (String flagName : section.getStringList("flags")) {
                try {
                    flags.add(BarFlag.valueOf(flagName.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    throw new InvalidConfigurationException("Unknown bar flag: " + flagName);
                }
            }
            this.flags = flags.toArray(new BarFlag[0]);
        }

        public String title() {
            return this.title;
        }

        public BarColor color() {
            return this.color;
        }

        public BarStyle style() {
            return this.style;
        }

        public BarFlag[] flags() {
            return this.flags;
        }

        public BossBar create(Server server, ChatColor color) {
            Objects.requireNonNull(server, "server");
            String title = Objects.toString(Objects.toString(color, "") + this.title(), "");
            return server.createBossBar(title, this.color(), this.style(), this.flags());
        }
    }
}
