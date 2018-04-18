import React from "react";
import PropTypes from "prop-types";
import { BallPulseLoading } from "../LoadingIcon/LoadingIcon";
import { Modal, Button, Table } from "react-bootstrap";
import { Cloud } from "../../../authentication/SDUCloudObject";
import { BreadCrumbs } from "../Breadcrumbs"
import { sortFilesByTypeAndName, createFolder, getFilenameFromPath, getTypeFromFile, getParentPath } from "../../UtilityFunctions";
import PromiseKeeper from "../../PromiseKeeper";
import { DashboardModal } from "uppy/lib/react";
import { dispatch } from "redux";
import { changeUppyRunAppOpen } from "../../Actions/UppyActions";

class FileSelector extends React.Component {
    constructor(props, context) {
        super(props, context);
        this.state = {
            promises: new PromiseKeeper(),
            returnObject: props.returnObject,
            currentPath: `${Cloud.homeFolder}`,
            loading: false,
            files: [],
            modalShown: false,
            breadcrumbs: [],
            onFileSelectionChange: props.onFileSelectionChange,
            uppyOnUploadSuccess: null,
        };

        this.openModal = this.openModal.bind(this);
        this.closeModal = this.closeModal.bind(this);
        this.getFiles = this.getFiles.bind(this);
        this.setSelectedFile = this.setSelectedFile.bind(this);
        this.onUppyClose = this.onUppyClose.bind(this);
        this.uppyOnUploadSuccess = this.uppyOnUploadSuccess.bind(this);
    }

    uppyOnUploadSuccess(file, resp, uploadURL) {
        if (!this.props.allowUpload) return;
        // TODO This is a hack.
        let apiIndex = uploadURL.indexOf("/api/");
        if (apiIndex === -1) throw "Did not expect upload URL to not contain /api/";

        let apiEndpoint = uploadURL.substring(apiIndex + 5);

        Cloud.head(apiEndpoint).then(it => {
            console.log("Got a response back!");
            let path = it.request.getResponseHeader("File-Location");
            let lastSlash = path.lastIndexOf("/");
            if (lastSlash === -1) throw "Could not parse name of path: " + path;
            let name = path.substring(lastSlash + 1);
            let fileObject = {
                path: path,
                name: name,
            };
            this.props.uploadCallback(fileObject);
        });
    };


    openModal() {
        this.setState(() => ({
            modalShown: true
        }));
    }

    closeModal() {
        this.setState(() => ({
            modalShown: false
        }));
    }

    componentDidMount() {
        this.getFiles(`/home/${Cloud.username}`);
    }

    componentWillUnmount() {
        this.state.promises.cancelPromises();
    }

    onUppyClose() {
        this.props.uppy.off("upload-success", this.state.uppyOnUploadSuccess);
        this.setState(() => ({
            uppyOnUploadSuccess: null,
        }));
    }

    setSelectedFile(file) {
        let fileCopy = { path: file.path };
        this.setState(() => ({
            modalShown: false,
        }));
        this.props.uploadCallback(fileCopy);
    }

    getFiles(path) {
        this.setState(() => ({ loading: true }));
        this.state.promises.makeCancelable(Cloud.get(`files?path=${path}`)).promise.then(req => {
            this.setState(() => ({
                files: sortFilesByTypeAndName(req.response, true),
                loading: false,
                currentPath: path,
            }));
        });
    }

    render() {
        const onUpload = () => {
            if (!this.props.allowUpload) return;
            this.context.store.dispatch(changeUppyRunAppOpen(true));
            let uppy = this.props.uppy;
            uppy.reset();
            uppy.once("upload-success", this.uppyOnUploadSuccess);
        };
        const path = this.props.path ? this.props.path : "";
        const uploadButton = this.props.allowUpload ? (<UploadButton onClick={onUpload} />) : null;
        const removeButton = this.props.remove ? (<RemoveButton onClick={this.props.remove} />) : null;
        return (
            <div>
                <div className="input-group">
                    <span className="input-group-btn">
                        <Button onClick={this.openModal}
                            type="button"
                            className="btn btn-default">Browse files
                        </Button>
                    </span>
                    <input className="form-control readonly" required={this.props.isRequired} type="text"
                        placeholder={"No file selected"}
                        value={path} />
                    {uploadButton}
                    {removeButton}
                </div>
                <Modal show={this.state.modalShown} onHide={this.closeModal}>
                    <Modal.Header closeButton>
                        <Modal.Title>File selector</Modal.Title>
                    </Modal.Header>
                    <BreadCrumbs currentPath={this.state.currentPath} navigate={this.getFiles} />
                    <BallPulseLoading loading={this.state.loading} />
                    <FileSelectorBody
                        loading={this.state.loading}
                        onClick={this.setSelectedFile}
                        files={this.state.files}
                        getFiles={this.getFiles}
                        currentPath={this.state.currentPath}
                    />
                </Modal>
            </div>)
    }
}

const FileSelectorBody = (props) => {
    if (props.loading) {
        return null;
    }
    if (!props.files.length) {
        return (
            <h4 className="col-md-offset-1">
                <small>No files in current folder.</small>
            </h4>
        );
    }
    console.log(props.currentPath);
    return (
        <Modal.Body>
            <div className="pre-scrollable">
                <Table className="table table-hover">
                    <thead>
                        <tr>
                            <th>Filename</th>
                        </tr>
                    </thead>
                    <tbody>
                        <ReturnFolder currentPath={removeTrailingSlash(props.currentPath)} getFiles={props.getFiles} />
                        <FileList files={props.files} onClick={props.onClick} getFiles={props.getFiles} />
                    </tbody>
                </Table>
            </div>
            <Button className="btn btn-info" onClick={() => createFolder(props.currentPath)}>
                Create new folder
            </Button>
        </Modal.Body>)
};

const ReturnFolder = ({ currentPath, getFiles }) =>
    !(currentPath !== Cloud.homeFolder) || !(currentPath !== "/home") ? null : (
        <tr className="row-settings clickable-row" style={{ cursor: "pointer" }}>
            <td onClick={() => getFiles(getParentPath(currentPath))}>
                <a><i className="ion-android-folder" /> ..</a>
            </td>
        </tr>);

const UploadButton = (props) => (<span className="input-group-addon btn btn-info" onClick={() => props.onClick()}>Upload file</span>);
const RemoveButton = (props) => (<span className="input-group-addon btn btn" onClick={() => props.onClick()}>✗</span>)

const FileList = ({ files, getFiles, onClick }) => {
    return !files.length ? null :
        (<React.Fragment>
            {files.map((file, index) =>
                file.type === "FILE" ?
                    (<tr key={index} className="gradeA row-settings" style={{ cursor: "pointer" }}>
                        <td onClick={() => onClick(file)}><span
                            className={getTypeFromFile(file.path)} /> {getFilenameFromPath(file.path)}
                        </td>
                    </tr>)
                    : (<tr key={index} className="row-settings clickable-row" style={{ cursor: "pointer" }}>
                        <td onClick={() => getFiles(file.path)}>
                            <a><i className="ion-android-folder" /> {getFilenameFromPath(file.path)}</a>
                        </td>
                    </tr>)
            )}
        </React.Fragment>);
}

FileSelector.contextTypes = {
    store: PropTypes.object.isRequired,
};

const removeTrailingSlash = (path) =>
    path.endsWith("/") ? path.slice(0, path.length - 1) : path;

export default FileSelector;