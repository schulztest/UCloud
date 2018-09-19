import * as React from "react";
import { create } from "react-test-renderer";
import { shallow, configure } from "enzyme";
import * as Adapter from "enzyme-adapter-react-16";
import { Uploader } from "Uploader";
import { configureStore } from "Utilities/ReduxUtilities";
import { initUploads, initFiles } from "DefaultObjects";
import uploader from "Uploader/Redux/UploaderReducer";
import files from "Files/Redux/FilesReducer";
import { Provider } from "react-redux";
import { MemoryRouter } from "react-router";
import * as UploaderActions from "Uploader/Redux/UploaderActions";


configure({ adapter: new Adapter() });

describe("Uploader", () => {
    test("Closed Uploader component", () => {
        const store = configureStore({
            files: initFiles({ homeFolder: "/home/user@test.dk/" }),
            uploader: initUploads()
        }, { files, uploader });
        expect(create(
            <Provider store={store}>
                <MemoryRouter>
                    <Uploader />
                </MemoryRouter>
            </Provider>
        ).toJSON()).toMatchSnapshot();
    });

    // FIXME Tests modal, which requires accessing the portal it is being rendered in?
    test.skip("Open Uploader component", () => {
        const store = configureStore({
            files: initFiles({ homeFolder: "/home/user@test.dk/" }),
            uploader: initUploads()
        }, { files, uploader });
        store.dispatch(UploaderActions.setUploaderVisible(true));
        expect(create(
            <Provider store={store}>
                <MemoryRouter>
                    <Uploader />
                </MemoryRouter>
            </Provider>
        ).toJSON()).toMatchSnapshot();
    });

    test.skip("Render Uploader component with files", () => {
        const store = configureStore({
            files: initFiles({ homeFolder: "/home/user@test.dk/" }),
            uploader: initUploads()
        }, { files, uploader });
        store.dispatch(UploaderActions.setUploaderVisible(false));
        store.dispatch(UploaderActions.setUploads([{
            file: new File([], "file"),
            isUploading: false,
            progressPercentage: 0,
            extractArchive: false,
            uploadXHR: undefined
        }]));
        expect(create(
            <Provider store={store}>
                <MemoryRouter>
                    <Uploader />
                </MemoryRouter>
            </Provider>
        ).toJSON()).toMatchSnapshot();
    });
});