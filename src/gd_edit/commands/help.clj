(ns gd-edit.commands.help
  (:require [clojure.string :as str]
            [gd-edit.utils :as u]))


(def command-help-map
  "
  Command help map is a list of 'help-items', where each is a tuple of
  [command-name brief-help-text detail-help-text]
  "
  [["exit"  "Exits the program"]
   ["q"     "Query the database"
    (str/join "\n"
              ["Query the database using a series of conditions in the form of"
               "  <partial fieldname> <operator> <value>"
               ""
               "<partial fieldname> can also be one of the following special keywords:"
               " 'recordname' matches against the name/path of the db record"
               " 'key' matches some key/fieldname in the record"
               " 'value' matches some value entry in the record"
               ""
               "valid operators are: ~ *= = != < > <= >="
               " ~ and *= are used for partial string matches"
               ""
               "Example 1:"
               " q recordname~weapons"
               ""
               " This says to return a list of records that has the word 'weapons' as part of"
               " the recordname. Since weapon records seem to mostly live under"
               " 'records/items/gearweapons', this will effectively return all weapons in the"
               " game."
               ""
               "Example 2:"
               " q recordname~weapons value~legendary"
               ""
               " In addition to matching against all weapons, as we saw in example 1, the added"
               " 'value~legendary' condition will narrow the list down to only records where"
               " some field contains the string 'legendary'."
               ""
               "Example 3:"
               " q recordname~weapons value~legendary levelreq < 50"
               ""
               " The new clause 'levelreq < 50' means that we're looking for records"
               " with a fieldname that partially matches 'levelreq' and where the value"
               " of that field is '< 50'"
               ""
               "Example 4:"
               " q recordname~weapons/blunt1h order offensivePhysicalMax"
               ""
               " Normally, the returned results are ordered by their recordname. However, you"
               " can ask the editor to order the results by any field you'd like ."])]

   ["qshow" "Show the next page in the query result"]
   ["qn"    "Alias for qshow"]

   ["db"    "Explore the database interactively"
    (str/join "\n"
              ["Syntax: db <partially matched path>"
               ""
               "The query command helps locate exact db records if you know what you're"
               "looking for. But if you don't know what's in the db to begin with, it will"
               "be difficult to come up with a useful query."
               ""
               "This command lets you explore the db interactively instead of querying"
               "against it."
               ""
               "Try the following commands:"
               " db"
               " db record/items"
               " db r/item/weapons/melee/d011_blunt2h.dbr"
               ""
               "If supplied with a path that indicates a 'directory', the results will show you"
               "all items residing in that directory. If the supplied path indicates a db record"
               "it will show you the contents of that record."
               ""
               "Lastly, each 'component' in the path can be partially matched, so you don't have"
               "to enter the exact path all the time. It works as long as you supply enough of"
               "the component to uniquely identify a directory or record."])]

   ["show"  "Explore the save file data"
    (str/join "\n"
              ["Syntax: show <partially matched path>"
               ""
               "This command is used to examine variables in the loaded character save file."
               ""
               "Example 1:"
               " show"
               ""
               " This shows a top level overview of all fields in the save file."
               ""
               "Example 2:"
               " show level"
               ""
               " This filters for fields that contains the world 'level'"
               ""
               "Example 3:"
               " show equipment"
               ""
               " If the filter narrows down matches to a single item, the contents of that"
               " item is displayed. In this case, the editor will show you all the contents"
               " of items you currently have equipped."
               ""
               "Example 4:"
               " show equipment/0"
               ""
               " The 'equipment' field is a collection of 12 items. This command says to show"
               " only the first item. When display an item, the editor will show the full name"
               " of the item as well as any related db records."])]

   ["ls"  "Alias for \"show\""]

   ["set"   "Set fields in the save file"
    (str/join "\n"
              ["Syntax: set <partially matched path to field> <new value>"
               ""
               "This command is used to alter field values in the loaded character save file."
               "Any fields that can be found using the 'show' command can be altered this way."
               ""
               "Example 1:"
               " set character-level 64"
               ""
               " This sets the character level to 64."
               ""
               "Example 2:"
               " set stash-items/0/stack-count 100"
               ""
               " Set the first item in your stash to a stack of 100."
               ""
               "Example 3:"
               " set weaponsets/0/items/0 \"legion warhammer of valor\""
               ""
               " Usually, the set command just puts the specified new value into the field"
               " specified by the partial path. So it's possible to change an item's basename,"
               " prefix, suffix, or any other field directly."
               ""
               " However, in the case of dealing with an item, it's actually very painful to"
               " query for the record to use as a basename, then repeat for the prefix and the"
               " suffix. So in the case where the partial path points at an item, it's possible"
               " to just supply the name of the item you want. The editor will try its best to"
               " figure the right combination of basename, prefix, and suffix. It also takes"
               " into consideration of the character that has currently been loaded."
               " "])]

   ["remove" "Removes an item from the specified collection"
    (str/join "\n"
              ["Syntax: remove <partially matched path to item>"
               ""
               "Example 1:"
               " remove inv/0/items/0"
               ""
               " This removes the first listed item in the first inventory bag"
               ""
               "Example 2:"
               " remove inv/0/items/*"
               ""
               " This removes all the items in the first inventory bag"])]

   ["rm" "Alias for \"remove\""]

   ["delete" "Delete a character"
    (str/join "\n"
              ["Moves a character (and its folder) to the recycle bin."
               ""
               "Syntax 1: delete"
               ""
               "Show a menu to choose a character for deletion"
               ""
               ""
               "Syntax 2: delete <character name>"
               ""
               "Deletes the character with the specified name."
               ""
               "Example:"
               " delete Odie"
               ""
               ""
               "Syntax 3: delete \"C:\\Users\\<userdir>\\Documents\\my games\\Grim Dawn\\save\\main\\_Odie\\player.gdc\""
               ""
               "Deletes the character at the specified path."])]

   ["find" "Find some character data by name"
    (str/join "\n"
              ["Syntax: find <a-name>"
               ""
               "This command will locate item, skill, or faction data somewhere in your character"
               "data by name. After locating the exact location, it is then easy to modify the entry"
               "using the set command."
               ""
               "Example:"
               "> find tonic"
               "|"
               "| Tonic of Mending: inventory-sacks/0/inventory-items/0"
               ""
               "> set inventory-sacks/0/inventory-items/0/stack-count 99"])]
   ["find all" "Find some character data by name in all locatable characters"
    (str/join "\n"
              ["Syntax: find all <a-name>"
               ""
               "This command works in the same way as 'find' except it will look through"
               "all characters that the editor is able to locate."])]
   ["load"  "Load from a save file"
    (str/join "\n"
              ["Load a character file"
               ""
               "Syntax 1: load"
               ""
               "Show a menu to choose a character for loading"
               ""
               ""
               "Syntax 2: load <character name>"
               ""
               "Loads the character with the specified name."
               ""
               "Example:"
               " load Odie"
               ""
               ""
               "Syntax 3: load \"C:\\Users\\<userdir>\\Documents\\my games\\Grim Dawn\\save\\main\\_Odie\\player.gdc\""
               ""
               "Loads the character at the specified path."])]
   ["write" "Writes out the character that is currently loaded"
    (str/join "\n"
              ["Syntax: write"
               ""
               "Writes out the currently loaded character."
               ""
               "Syntax: write <new-character-name> <optional \"mod\">"
               ""
               "Makes a copy of the currently loaded character."
               "If the the optional \"mod\" flag is added, the character will be saved as a"
               "mod character"])]
   ["write stash" "Writes out the transfer stash if it is loaded"]
   ["ws" "Alias for \"write stash\""]
   ["write character-list" "Writes all characters to a csv file"
    (str/join "\n"
              ["Syntax: write character-list <filename>"])]
   ["class" "Displays the classes/masteries of the loaded character"]
   ["class list" "Display classes/masteries known ot the editor"]
   ["class add" "Add a class/mastery by name"]
   ["class remove" "Remove a class/mastery by name"]
   ["savedir" "Sets the save game directory to a path"
    (str/join "\n"
              ["Syntax: savedir <full path to save game directory>"])]
   ["savedir clear" "Removes the previous set game directory"]
   ["gamedir" "Sets the game installation directory to a path"
    (str/join "\n"
              ["Syntax: gamedir <full path to save game installation directory>"])]
   ["gamedir clear" "Removes the previously set game installation directory"]
   ["mod" "Displays the mod currently selected"]
   ["mod pick" "Picks an installed mod to activate"]
   ["mod clear" "Unselect the currently selected mod"]
   ["level" "Set the level of the loaded character to a new value"
    (str/join "\n"
              ["Syntax: level <new level>"
               ""
               "This command can level the character both up and down and will update the following fields:"
               "- character level"
               "- attribute points"
               "- skill points"
               "- experience points"])]
   ["respec" "Respecs the loaded character"
    (str/join "\n"
              ["Syntax: respec <respec-type>"
               ""
               "Valid respec types:"
               " attributes - refund all attribute points spent"
               " skills - remove all masteries & skills and refund skill points spent"
               " devotions - remove all devotions and refund devotion points spent"
               " all - combination of all of the above"])]
   ["update" "Update to the latest version of gd-edit"]
   ["swap-variant" "Swap the item out for one of its variants"
    (str/join "\n"
              ["Syntax: swap-variant <path-to-item> <optional target-type>"
               ""
               "This command will try to locate any variants for the item at the path,"
               "then lets you choose from an onscreen menu to swap with."
               ""
               "Target type is an optional parameter and can be any of the following: "
               "\tbasename, prefix, suffix."
               "If a target type isn't specified, it's assumed that the user wants to"
               "change the basename of the item."])]

   ["batch" "Executes commands in a batch file"
    (str/join "\n"
              ["Syntax: batch <path-to-batch-file>"
               ""
               "The batch file can contain any command the editor is able to deal with."
               "After running through the commands, control will return back to the"
               "command-prompt."])]

   ["batch character" "Execute commands in batch file for all known characters"
    (str/join "\n"
              ["Syntax: batch character <path-to-batch-file>"
               ""
               "Load each character file found by the editor, run the commands indicated by the batch file, then write the character file before proceeding to the next character."])]

   ["batch item" "Batch creates several copies of the specified item"
    (str/join "\n"
              ["Syntax: batch item <path-to-place-items> <name of item> <optional max-item-level>"
               ""
               "If it is possible to create the specified item, this command will place as many"
               "copies as it is possible to fit into specified container."
               ""
               "Note that the expected parameters are the same as the item creation variant of \"set\"."])]

   ["shrine list" "Lists all known shrines"]
   ["gate list" "Lists all known rift gates"]
   ["make-char" "Make a local character using a GrimTools character"
    (str/join "\n"
              ["Syntax: make-char <character-url-or-id>"
               ""
               "Ex: "
               "  make-char https://www.grimtools.com/calc/JVljdR7N"
               "  make-char JVljdR7N"])]])

(defn detail-help-text
  [help-item]

  (nth help-item 2))

(defn brief-help-text
  [help-item]

  (nth help-item 1))

(defn get-help-item
  [command-name]

  (->> command-help-map
       (filter #(= command-name (first %1)))
       (first)))

(defn help-handler
  [[input tokens]]

  (cond
    ;; If there are no other parameters,
    ;; show the list of commands and a short help text.
    (= 0 (count tokens))
    (let [command-names (map #(first %1) command-help-map)
          max-name-length (reduce (fn [max-length item]
                                    (if (> (count item) max-length)
                                      (count item)
                                      max-length)
                                    )
                                  0 command-names)]
      ;; Print the name of the command followed by the short help text
      (doseq [help-item command-help-map]
        (u/print-line (format (format "%%-%ds     %%s" max-name-length) (first help-item) (second help-item))))
      (u/newline-)
      (u/print-line (str/join "\n"
                         ["To more detailed help text on a command, run: "
                          " help <command>"
                          ""
                          "Need more help? Check the docs!"
                          "\thttps://odie.github.io/gd-edit-docs/faq/"
                          "\t   and"
                          "\thttps://odie.github.io/gd-edit-docs/commands/"
                          ])))

    :else
    ;; If the user is looking for help text on a specific command,
    ;; try to find the help text.
    ;; Display the help text (or lack of one) as appropriate
    (let [command (str/join " " tokens)
          help-item (get-help-item command)]
      (cond
        (nil? help-item)
        (u/print-line (format "Unknown command '%s'" command))

        (> (count help-item) 2)
        (u/print-line (nth help-item 2))

        :else
        (do
          (u/print-line (format "%s\t%s" (first help-item) (second help-item)))
          (u/print-line)
          (u/print-line (format "Sorry, '%s' has no detailed help text." command)))))))
