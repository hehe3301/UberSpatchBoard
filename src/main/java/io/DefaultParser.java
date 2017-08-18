/*
 * The MIT License
 *
 * Copyright 2017 .
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io;

import de.targodan.usb.data.Platform;
import de.targodan.usb.data.Rat;
import de.targodan.usb.data.Report;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 * @author Luca Corbatto
 */
public class DefaultParser implements Parser {
    protected Handler handler;
    
    protected Pattern commandPattern;
    protected Pattern callPattern;
    protected Pattern reportPattern;
    
    protected Pattern twoArgumentsPattern;
    protected Pattern threeArgumentsPattern;
    
    protected String[] supportedReports = {
        // IMPORTANT: If this needs to be changed look at the way parseAndHandleReport
        //            works and it will still then. Any postfix of any report must not be
        //            a valid report on its own.
        "fr", "wr", "wb", "bc", "comm", "comms", "inst", "party",
    };
    
    public DefaultParser() {
        this.handler = null;
        
        this.commandPattern = Pattern.compile("^(?<cmd>(?:!\\S+|go))\\s+(?<params>.*)$");
        this.callPattern = Pattern.compile("(^|.*(\\s|,))(?<jumps>\\d+)(j|J)(\\s|$).*(?<case>(?:#?\\d+|\\S+)?)");
        String reportRegex = "(^|.*(\\s|,))(?<type>(";
        for(int i = 0; i < this.supportedReports.length; ++i) {
            reportRegex += this.supportedReports[i];
            if(i+1 < this.supportedReports.length) {
                reportRegex += "|";
            }
        }
        reportRegex += "))(?<state>\\+|-)(\\s|$).*(?<case>(?:#?\\d+|\\S+)?)";
        this.reportPattern = Pattern.compile(reportRegex, Pattern.CASE_INSENSITIVE);
        
        this.twoArgumentsPattern = Pattern.compile("^(\\S+)\\s+(.*)$");
        this.threeArgumentsPattern = Pattern.compile("^(\\S+)\\s+(\\S+)\\s+(.*)$");
    }
    
    @Override
    public void registerHandler(Handler handler) {
        this.handler = handler;
    }

    @Override
    public ParseResult parseAndHandle(IRCMessage message) {
        if(this.handler == null) {
            throw new IllegalStateException("Call RegisterHandler before calling ParseAndHandle.");
        }
        
        boolean wasCommand = this.parseAndHandleCommand(message);
        if(wasCommand) {
            return ParseResult.WAS_COMMAND;
        }
        
        boolean wasCall = this.parseAndHandleCall(message);
        boolean wasReport = this.parseAndHandleReport(message);
        if(wasCall && wasReport) {
            return ParseResult.WAS_CALL_AND_REPORT;
        }
        if(wasCall) {
            return ParseResult.WAS_CALL;
        }
        if(wasReport) {
            return ParseResult.WAS_REPORT;
        }
        
        return ParseResult.IGNORED;
    }
    
    protected boolean parseAndHandleCommand(IRCMessage message) {
        Matcher m = this.commandPattern.matcher(message.getContent().trim());
        if(!m.matches()) {
            return false;
        }
        
        String cmd = m.group("cmd");
        String params = m.group("params");
        
        Command.Type cmdType;
        try {
            cmdType = this.parseCommandType(cmd);
        } catch(Exception ex) {
            Logger.getLogger(DefaultParser.class.getName()).log(Level.SEVERE, null, ex);
            return false;
        }
        
        String[] paramArray;
        try {
            paramArray = this.splitArgumentsOfCommand(cmdType, params);
        } catch(Exception ex) {
            Logger.getLogger(DefaultParser.class.getName()).log(Level.SEVERE, null, ex);
            return false;
        }
        
        Command command = new Command(cmdType, paramArray);
        this.handler.handleCommand(command);
        
        return true;
    }
    
    protected boolean parseAndHandleCall(IRCMessage message) {
        Matcher m = this.callPattern.matcher(message.getContent().trim());
        if(!m.matches()) {
            return false;
        }
        
        Rat call = new Rat(message.getSender());
        call.setJumps(Integer.valueOf(m.group("jumps")));
        
        this.handler.handleCall(call, m.group("case"));
        
        return true;
    }
    
    protected boolean parseAndHandleReport(IRCMessage message) {
        // Dirty hack for supporting multiple reports in a single line, avert your eyes.
        boolean matchedAtLeastOnce = false;
        String text = message.getContent();
        String caseIdentifier = null;
        while(true) /* will break when done */ {
            Matcher m = this.reportPattern.matcher(text);
            if(!m.matches()) {
                break;
            }
            matchedAtLeastOnce = true;
            
            String repType = m.group("type");
            String repState = m.group("state");
            if(caseIdentifier == null) {
                // First match is probably the best match
                caseIdentifier = m.group("case");
            }
            
            Report report = new Report(this.parseReportType(repType), repState.equals("+"));
            this.handler.handleReport(report);
            
            // Remove one char from front to find the other reports.
            text = text.substring(1);
        }
        
        return matchedAtLeastOnce;
    }
    
    protected Command.Type parseCommandType(String cmd) {
        cmd = cmd.toLowerCase();
        if(cmd.equals("go")) {
            return Command.Type.SOFT_ASSIGN;
        }
        
        if(cmd.charAt(0) != '!') {
            throw new IllegalArgumentException("Commands must start with '!' or be \"go\", got \""+cmd+"\"!");
        }
        
        switch(cmd) {
            case "active":
            case "inactive":
            case "deactivate":
                return Command.Type.TOGGLE_ACTIVE;
            
            case "go":
            case "assign":
            case "add":
                return Command.Type.HARD_ASSIGN;
                
            case "clear":
            case "close":
                return Command.Type.CLOSE;
                
            case "cmdr":
            case "commander":
                return Command.Type.SET_CMDR_NAME;
                
            case "codered":
            case "cr":
            case "casered":
                return Command.Type.TOGGLE_CODERED;
                
            case "grab":
                return Command.Type.GRAB;
                
            case "inject":
                return Command.Type.INJECT;
                
            case "ircnick":
            case "nick":
            case "nickname":
                return Command.Type.SET_IRCNICK;
                
            case "md":
                return Command.Type.MARK_DELETION;
                
            case "pc":
                return Command.Type.SET_PLATFORM_PC;
                
            case "ps":
                return Command.Type.SET_PLATFORM_PS;
                
            case "sub":
                return Command.Type.SUBSTITUTE;
                
            case "sys":
            case "system":
            case "loc":
            case "location":
                return Command.Type.SET_SYSTEM;
                
            case "unassing":
            case "rm":
            case "remove":
            case "standdown":
                return Command.Type.UNASSIGN;
                
            case "xb":
                return Command.Type.SET_PLATFORM_XB;
        }
        
        throw new IllegalArgumentException("Command \""+cmd+"\" not recognized.");
    }
    
    protected String[] splitArgumentsOfCommand(Command.Type cmd, String params) {
        params = params.trim();
        
        Matcher m;
        switch(cmd) {
            case TOGGLE_ACTIVE:
            case TOGGLE_CODERED:
            case GRAB:
            case SET_PLATFORM_PC:
            case SET_PLATFORM_PS:
            case SET_PLATFORM_XB:
                return new String[] {params};
                
            case SOFT_ASSIGN:
            case HARD_ASSIGN:
            case CLOSE:
            case UNASSIGN:
                return params.split("\\s+");
                
            case SET_CMDR_NAME:
            case INJECT:
            case SET_IRCNICK:
            case SET_SYSTEM:
            case MARK_DELETION:
                m = this.twoArgumentsPattern.matcher(params);
                if(!m.matches()) {
                    throw new IllegalArgumentException("Exactly two arguments are needed for command of type \""+cmd.toString()+"\". Got \""+params+"\".");
                }
                return new String[] {m.group(1), m.group(2)};
                
            case SUBSTITUTE:
                m = this.threeArgumentsPattern.matcher(params);
                if(!m.matches()) {
                    throw new IllegalArgumentException("Exactly three arguments are needed for command of type \""+cmd.toString()+"\". Got \""+params+"\".");
                }
                return new String[] {m.group(1), m.group(2), m.group(3)};
        }
        
        return new String[]{};
    }
    
    protected Report.Type parseReportType(String report) {
        report = report.toLowerCase();
        switch(report) {
            case "fr":
                return Report.Type.FR;
                
            case "wr":
                return Report.Type.WR;
                
            case "wb":
            case "bc":
                return Report.Type.BC;
                
            case "comm":
            case "comms":
                return Report.Type.COMMS;
                
            case "inst":
                return Report.Type.INST;
                
            case "party":
                return Report.Type.PARTY;
        }
        
        throw new IllegalArgumentException("Report \""+report+"\" unknown.");
    }
}
