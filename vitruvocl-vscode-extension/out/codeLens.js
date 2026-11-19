"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.OCLCodeLensProvider = void 0;
/**
 * The run buttons above the constraints: "Run All" above the first `context`, and one per
 * constraint header.
 */
const vscode = __importStar(require("vscode"));
const units_1 = require("./units");
class OCLCodeLensProvider {
    constructor() {
        this._onDidChangeCodeLenses = new vscode.EventEmitter();
        this.onDidChangeCodeLenses = this._onDidChangeCodeLenses.event;
    }
    refresh() {
        this._onDidChangeCodeLenses.fire();
    }
    provideCodeLenses(document) {
        const codeLenses = [];
        const text = document.getText();
        const lines = text.split('\n');
        const runAllLine = (0, units_1.firstContextLine)(text);
        if (runAllLine >= 0) {
            codeLenses.push(new vscode.CodeLens(new vscode.Range(runAllLine, 0, runAllLine, 0), {
                title: '▶ Run All',
                command: 'vitruvocl.runAllConstraints'
            }));
        }
        for (const header of (0, units_1.findConstraintHeaders)(text)) {
            codeLenses.push(new vscode.CodeLens(new vscode.Range(header.line, 0, header.line, lines[header.line].length), {
                title: '▶',
                command: 'vitruvocl.runConstraint',
                arguments: [header.name, document.uri]
            }));
        }
        return codeLenses;
    }
}
exports.OCLCodeLensProvider = OCLCodeLensProvider;
//# sourceMappingURL=codeLens.js.map